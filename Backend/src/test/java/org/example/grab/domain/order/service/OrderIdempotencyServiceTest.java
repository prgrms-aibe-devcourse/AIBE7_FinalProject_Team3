package org.example.grab.domain.order.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.IdempotencyResult;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderIdempotencyServiceTest {

    private static final IdempotencyKey IDEMPOTENCY_KEY = IdempotencyKey.from(
            "550e8400-e29b-41d4-a716-446655440000"
    );
    private static final RequestHash REQUEST_HASH = RequestHash.from("a".repeat(64));

    @Autowired
    private OrderIdempotencyService orderIdempotencyService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long buyerId;
    private Long sellerId;
    private Long dropId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        buyerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, display_name)
                VALUES (?, 'encoded-password', '멱등 구매자')
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com"
        );
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, '멱등 판매자', ?)
                RETURNING id
                """,
                Long.class,
                buyerId,
                "seller-" + uniqueValue + "@example.com"
        );
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id",
                Long.class,
                sellerId
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM orders WHERE buyer_id = ?", buyerId);
        jdbcTemplate.update("DELETE FROM drops WHERE id = ?", dropId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
    }

    @Test
    @DisplayName("같은 키와 같은 요청은 기존 주문을 반환하고 다른 요청은 충돌로 거부한다")
    void checksExistingOrder() {
        // given
        Order savedOrder = saveOrder(IDEMPOTENCY_KEY, REQUEST_HASH);

        // when
        IdempotencyResult<Order> replay = orderIdempotencyService.check(
                buyerId,
                IDEMPOTENCY_KEY,
                REQUEST_HASH
        );
        IdempotencyResult<Order> newRequest = orderIdempotencyService.check(
                buyerId,
                IdempotencyKey.from("123e4567-e89b-12d3-a456-426614174000"),
                REQUEST_HASH
        );

        // then
        assertThat(replay.status()).isEqualTo(IdempotencyResult.Status.REPLAY);
        assertThat(replay.getExistingResult()).hasValueSatisfying(order ->
                assertThat(order.getId()).isEqualTo(savedOrder.getId()));
        assertThat(newRequest.isNewRequest()).isTrue();
        assertThatThrownBy(() -> orderIdempotencyService.check(
                buyerId,
                IDEMPOTENCY_KEY,
                RequestHash.from("b".repeat(64))
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode())
                            .isEqualTo(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
                    assertThat(businessException.getErrorCode().getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("같은 구매자와 멱등 키의 동시 주문은 한 건만 생성되고 최초 주문을 재조회한다")
    void createsOnlyOneOrderForConcurrentRequests() throws Exception {
        // given
        int requestCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> trySaveConcurrently(ready, start)),
                    executor.submit(() -> trySaveConcurrently(ready, start))
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            start.countDown();
            long successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(1);
            assertThat(orderRepository.countByBuyerIdAndIdempotencyKey(buyerId, IDEMPOTENCY_KEY.value()))
                    .isEqualTo(1);

            Order replayedOrder = orderIdempotencyService.resolveAfterConcurrentInsert(
                    buyerId,
                    IDEMPOTENCY_KEY,
                    REQUEST_HASH
            );
            assertThat(replayedOrder.getRequestHash()).isEqualTo(REQUEST_HASH.value());
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean trySaveConcurrently(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();

        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.executeWithoutResult(status -> orderRepository.saveAndFlush(createOrder(
                    IDEMPOTENCY_KEY,
                    REQUEST_HASH
            )));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private Order saveOrder(IdempotencyKey idempotencyKey, RequestHash requestHash) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> orderRepository.saveAndFlush(createOrder(
                idempotencyKey,
                requestHash
        )));
    }

    private Order createOrder(IdempotencyKey idempotencyKey, RequestHash requestHash) {
        return Order.create(
                "ORD-20260923-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                buyerId,
                dropId,
                idempotencyKey.value(),
                requestHash.value(),
                "멱등 테스트 상품",
                "멱등 판매자",
                10000,
                3000,
                ShippingAddress.of(
                        "홍길동",
                        "010-1234-5678",
                        "06236",
                        "서울시 강남구 테헤란로",
                        null,
                        null
                ),
                OffsetDateTime.now().plusMinutes(15)
        );
    }
}
