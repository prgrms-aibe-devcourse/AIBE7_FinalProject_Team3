package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 전환 배치가 실제 PostgreSQL 행 잠금 아래에서 SKIP LOCKED·멱등으로 동작하는지 확인한다(GR-18 4-3).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DropTransitionConcurrencyIntegrationTest {

    @Autowired
    private DropTransitionService dropTransitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private Long userId;
    private Long sellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        String unique = UUID.randomUUID().toString();
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', ?) RETURNING id",
                Long.class, unique + "@example.com", "판매자-" + unique);
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?) RETURNING id
                """,
                Long.class, userId, "seller-" + unique + "@example.com");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class, "TEST-" + unique);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // @SpringBootTest는 롤백하지 않으므로 이 테스트가 만든 DROP과 부모 행을 FK 순서대로 정리한다.
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("주문이 FOR SHARE를 보유하는 동안 전환 조회는 그 행을 건너뛰고, 잠금 해제 후 다음 실행에서 전환한다")
    void skipsRowHeldByShareLockThenTransitionsOnNextRun() throws Exception {
        // given: 종료 시각이 지난 GRAB. 다른 트랜잭션이 FOR SHARE로 잡고 있다.
        Long dropId = insertGrabDrop(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM drops WHERE id = ? FOR SHARE", Long.class, dropId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when: 잠금 보유 중 전환하면 SKIP LOCKED로 건너뛰어 0건
        long whileLocked = dropTransitionService.transition(now);
        assertThat(whileLocked).isZero();
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.GRAB);

        // 잠금 해제 후 다음 실행에서 전환
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        long afterRelease = dropTransitionService.transition(now);

        // then
        assertThat(afterRelease).isEqualTo(1);
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.ENDED);
    }

    @Test
    @DisplayName("여러 스레드가 동시에 실행해도 SKIP LOCKED와 멱등 처리로 중복 전환 없이 모두 처리된다")
    void concurrentRunsDoNotDoubleTransition() throws Exception {
        // given: 시작 대상 300건
        int total = 300;
        for (int i = 0; i < total; i++) {
            insertWishDrop(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                return dropTransitionService.transition(now);
            });
        }
        List<Future<Long>> futures = new ArrayList<>();
        for (Callable<Long> task : tasks) {
            futures.add(executor.submit(task));
        }

        // when
        start.countDown();
        long processed = 0;
        for (Future<Long> future : futures) {
            processed += future.get(30, TimeUnit.SECONDS);
        }

        // then: 총 처리 건수는 대상 수와 같고, 이 테스트가 만든 DROP은 모두 GRAB이며 중복 전환되지 않는다
        assertThat(processed).isEqualTo(total);
        Integer grabCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM drops WHERE seller_id = ? AND status = 'GRAB'", Integer.class, sellerId);
        assertThat(grabCount).isEqualTo(total);
    }

    private Long insertWishDrop(OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, 'WISH', '상품', '설명', 3000, '안내', ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, saleStartsAt, saleEndsAt);
    }

    private Long insertGrabDrop(OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at)
                VALUES (?, ?, 'GRAB', '상품', '설명', 3000, '안내', ?, ?, CURRENT_TIMESTAMP, ?)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, saleStartsAt, saleEndsAt, saleStartsAt);
    }

    private DropStatus statusOf(Long dropId) {
        return jdbcTemplate.queryForObject("SELECT status FROM drops WHERE id = ?", DropStatus.class, dropId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
