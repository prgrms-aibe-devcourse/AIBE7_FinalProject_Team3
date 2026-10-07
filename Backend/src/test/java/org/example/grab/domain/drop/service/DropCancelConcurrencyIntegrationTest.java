package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매자 취소가 DROP 행 잠금(FOR UPDATE)을 기다린 뒤, 잠금 획득 시점의 서버 시각으로 취소 가능 여부를
 * 재판정하는지 실제 PostgreSQL 잠금 아래에서 확인한다(GR-18 0-5).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DropCancelConcurrencyIntegrationTest {

    @Autowired
    private DropService dropService;

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
        executor = Executors.newFixedThreadPool(2);
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
    @DisplayName("취소와 시작 전환이 경합해도 취소는 거부되고, 이후 전환 실행에서 GRAB으로 수렴한다")
    void cancelAndStartTransitionRaceLeavesSingleValidState() throws Exception {
        // given: 판매 시작 시각이 이미 지난 WISH. 취소는 시작 후라 거부되어야 한다.
        Long dropId = insertWishDrop(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        CountDownLatch start = new CountDownLatch(1);
        Future<Long> transition = executor.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return dropTransitionService.transition(now);
        });
        Future<Drop> cancel = executor.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return dropService.cancel(sellerId, dropId, "재고 확보 실패");
            } catch (BusinessException e) {
                return null;
            }
        });
        start.countDown();

        // when
        transition.get(10, TimeUnit.SECONDS);
        Drop canceled = cancel.get(10, TimeUnit.SECONDS);

        // then: 취소는 거부된다(잠금 순서와 무관하게 시작 후 상태)
        assertThat(canceled).isNull();
        // 취소가 행 잠금을 먼저 잡으면 전환 조회가 SKIP LOCKED로 이 행을 건너뛰어 저장 상태가 잠시 WISH로 남을 수 있다.
        // 저장 상태 전환 지연은 설계상 허용 범위(TECHSTACK 1.1)이므로, 다음 실행에서 GRAB으로 수렴하는 것만 확인한다.
        dropTransitionService.transition(now);
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.GRAB);
    }

    @Test
    @DisplayName("주문이 FOR SHARE를 잡은 동안 취소가 대기하고, 잠금 획득 시점이 시작 이후면 취소가 거부된다")
    void cancelWaitsForLockAndRejectsWhenSaleStarted() throws Exception {
        // given: 판매 시작이 임박한 WISH. 잠금 대기 중 시작 시각이 지나도록 만든다.
        OffsetDateTime saleStartsAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        Long dropId = insertWishDrop(saleStartsAt);
        int baseline = waitingForLock();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM drops WHERE id = ? FOR SHARE", Long.class, dropId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when: 취소가 행 잠금을 기다린다. 잠금을 놓기 전에 판매 시작 시각을 넘긴다.
        Future<Drop> cancel = executor.submit(() -> dropService.cancel(sellerId, dropId, "재고 확보 실패"));
        awaitWaitingForLock(baseline + 1);
        Thread.sleep(1500);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        // then: 잠금 획득 후 now가 시작 시각을 지나 취소가 거부되고 WISH가 유지된다
        assertThatThrownBy(() -> cancel.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.WISH);
    }

    @Test
    @DisplayName("잠금 대기 중에도 시작 전이면 취소가 성공하고 CANCELED로 저장된다")
    void cancelSucceedsWhenStillBeforeSaleStart() throws Exception {
        // given
        Long dropId = insertWishDrop(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        int baseline = waitingForLock();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM drops WHERE id = ? FOR SHARE", Long.class, dropId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        Future<Drop> cancel = executor.submit(() -> dropService.cancel(sellerId, dropId, "재고 확보 실패"));
        awaitWaitingForLock(baseline + 1);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        // then
        assertThat(cancel.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(DropStatus.CANCELED);
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.CANCELED);
    }

    private Long insertWishDrop(OffsetDateTime saleStartsAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, 'WISH', '상품', '설명', 3000, '안내', ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, saleStartsAt, saleStartsAt.plusHours(2));
    }

    private DropStatus statusOf(Long dropId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM drops WHERE id = ?", DropStatus.class, dropId);
    }

    private int waitingForLock() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (waitingForLock() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("행 잠금 대기에 들어간 트랜잭션이 " + expected + "개가 되지 않았습니다.");
            }
            Thread.sleep(20);
        }
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
