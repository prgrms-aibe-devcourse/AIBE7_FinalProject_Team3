package org.example.grab.domain.wish.service;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class WishServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WishService wishService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long sellerId;
    private Long wishDropId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        sellerId = insertSeller(userId);
        wishDropId = insertWishDrop();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM wishes WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("같은 등록 요청을 반복해도 활성 WISH는 한 건이다")
    void register_isIdempotent() {
        // when
        WishResponse first = wishService.register(userId, wishDropId);
        WishResponse second = wishService.register(userId, wishDropId);

        // then
        assertThat(first.wishedAt()).isEqualTo(second.wishedAt());
        assertThat(countWishes()).isEqualTo(1);
        assertThat(countActiveWishes()).isEqualTo(1);
    }

    @Test
    @DisplayName("취소 후 재등록하면 새 행 없이 같은 행이 재활성화된다")
    void registerAfterCancel_reusesRow() {
        // given
        wishService.register(userId, wishDropId);
        wishService.cancel(userId, wishDropId);
        assertThat(countActiveWishes()).isZero();

        // when
        wishService.register(userId, wishDropId);

        // then
        assertThat(countWishes()).isEqualTo(1);
        assertThat(countActiveWishes()).isEqualTo(1);
        assertThat(isCanceled()).isFalse();
    }

    @Test
    @DisplayName("취소하면 canceled_at이 기록되어 활성 집계에서 빠진다")
    void cancel_removesFromActive() {
        // given
        wishService.register(userId, wishDropId);

        // when
        wishService.cancel(userId, wishDropId);

        // then
        assertThat(isCanceled()).isTrue();
        assertThat(countActiveWishes()).isZero();
    }

    @Test
    @DisplayName("활성 WISH가 없어도 취소는 멱등하게 성공한다")
    void cancel_isIdempotentWithoutActiveWish() {
        // given: 등록한 적 없는 상태
        assertThatCode(() -> wishService.cancel(userId, wishDropId)).doesNotThrowAnyException();

        // when: 등록 후 이미 취소된 상태에서 다시 취소
        wishService.register(userId, wishDropId);
        wishService.cancel(userId, wishDropId);

        // then
        assertThatCode(() -> wishService.cancel(userId, wishDropId)).doesNotThrowAnyException();
        assertThat(countActiveWishes()).isZero();
    }

    @Test
    @DisplayName("GRAB DROP은 등록·취소가 거부되고 DB가 바뀌지 않는다")
    void grabDrop_isNotWishable() {
        // given
        Long grabDropId = insertGrabDrop();

        // when & then
        assertThatThrownBy(() -> wishService.register(userId, grabDropId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        assertThatThrownBy(() -> wishService.cancel(userId, grabDropId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        assertThat(countWishesForDrop(grabDropId)).isZero();
    }

    @Test
    @DisplayName("ENDED DROP은 등록·취소가 거부되고 DB가 바뀌지 않는다")
    void endedDrop_isNotWishable() {
        // given
        Long endedDropId = insertEndedDrop();

        // when & then
        assertThatThrownBy(() -> wishService.register(userId, endedDropId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        assertThatThrownBy(() -> wishService.cancel(userId, endedDropId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        assertThat(countWishesForDrop(endedDropId)).isZero();
    }

    @Test
    @DisplayName("같은 사용자의 동시 첫 등록도 모두 성공하고 행은 한 건이다")
    void register_concurrentlyCreatesSingleRow() throws Exception {
        // given
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<WishResponse>> results = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return wishService.register(userId, wishDropId);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            start.countDown();

            // then
            for (Future<WishResponse> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).wished()).isTrue();
            }
            assertThat(countWishes()).isEqualTo(1);
            assertThat(countActiveWishes()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("취소된 WISH를 동시에 재등록해도 두 응답의 wishedAt과 DB 활성화 시각이 하나로 같다")
    void reRegister_concurrentlyAfterCancelYieldsSingleActivatedAt() throws Exception {
        // given: 취소된 WISH 한 건
        wishService.register(userId, wishDropId);
        wishService.cancel(userId, wishDropId);
        assertThat(isCanceled()).isTrue();

        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<WishResponse>> results = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return wishService.register(userId, wishDropId);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            start.countDown();
            List<java.time.OffsetDateTime> wishedAtList = new ArrayList<>();
            for (Future<WishResponse> result : results) {
                wishedAtList.add(result.get(10, TimeUnit.SECONDS).wishedAt());
            }

            // then: 모든 응답의 wishedAt이 같고, DB 활성화 시각도 그 값 하나다
            assertThat(wishedAtList).doesNotContainNull().containsOnly(wishedAtList.get(0));
            assertThat(activatedAtOf()).isEqualTo(wishedAtList.get(0));
            assertThat(countWishes()).isEqualTo(1);
            assertThat(countActiveWishes()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시 재등록과 취소가 경합하면 최종 상태는 하나이고 마지막 커밋 결과와 일치한다")
    void reRegisterAndCancelRaceLeavesConsistentState() throws Exception {
        // given
        wishService.register(userId, wishDropId);
        wishService.cancel(userId, wishDropId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<WishResponse> register = executor.submit(() -> {
                start.await();
                return wishService.register(userId, wishDropId);
            });
            Future<?> cancel = executor.submit(() -> {
                start.await();
                try {
                    wishService.cancel(userId, wishDropId);
                } catch (BusinessException ignored) {
                    // 시작 시각 경과 등으로 거부될 수 있다(WISH DROP은 시작 전이라 현재는 성공한다)
                }
                return null;
            });

            // when
            start.countDown();
            register.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);

            // then: 행은 하나이고 활성/취소 중 하나의 일관된 상태다
            assertThat(countWishes()).isEqualTo(1);
            boolean canceled = isCanceled();
            assertThat(countActiveWishes()).isEqualTo(canceled ? 0 : 1);
        } finally {
            executor.shutdownNow();
        }
    }

    private java.time.OffsetDateTime activatedAtOf() {
        return jdbcTemplate.queryForObject(
                "SELECT activated_at FROM wishes WHERE user_id = ? AND drop_id = ?",
                java.time.OffsetDateTime.class,
                userId,
                wishDropId);
    }

    private boolean isCanceled() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT canceled_at IS NOT NULL FROM wishes WHERE user_id = ? AND drop_id = ?",
                Boolean.class,
                userId,
                wishDropId));
    }

    private int countWishes() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishes WHERE user_id = ? AND drop_id = ?",
                Integer.class,
                userId,
                wishDropId);
    }

    private int countActiveWishes() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishes WHERE user_id = ? AND drop_id = ? AND canceled_at IS NULL",
                Integer.class,
                userId,
                wishDropId);
    }

    private int countWishesForDrop(Long dropId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishes WHERE drop_id = ?", Integer.class, dropId);
    }

    private Long insertUser() {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class,
                unique + "@example.com",
                "회원-" + unique);
    }

    private Long insertSeller(Long ownerUserId) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                ownerUserId,
                "seller-" + unique + "@example.com");
    }

    private Long insertWishDrop() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at)
                VALUES (?, 1, 'WISH', '한정판 상품', '설명', 3000, '배송 안내',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '2 hours',
                        CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                sellerId);
    }

    private Long insertGrabDrop() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at)
                VALUES (?, 1, 'GRAB', '한정판 상품', '설명', 3000, '배송 안내',
                        CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 hour')
                RETURNING id
                """,
                Long.class,
                sellerId);
    }

    private Long insertEndedDrop() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, 1, 'ENDED', '한정판 상품', '설명', 3000, '배송 안내',
                        CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour',
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '2 hours',
                        CURRENT_TIMESTAMP, 'TIME_EXPIRED')
                RETURNING id
                """,
                Long.class,
                sellerId);
    }
}
