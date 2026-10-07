package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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
@Import(TestcontainersConfiguration.class)
class OrderCreateIntegrationTest {

    @Autowired
    private OrderCreateTransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long buyerId;
    private Long sellerId;
    private Long dropId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        buyerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com",
                "구매자-" + uniqueValue
        );
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (
                    user_id, brand_name, contact_email, status, reviewed_by, reviewed_at
                ) VALUES (?, '테스트 브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                buyerId,
                "seller-" + uniqueValue + "@example.com",
                buyerId
        );
        dropId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (
                    seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                    sale_starts_at, sale_ends_at, published_at, grab_started_at
                ) VALUES (?, 1, 'GRAB', '한정판 상품', '설명', 3000, '배송 안내',
                          CURRENT_TIMESTAMP - INTERVAL '1 minute',
                          CURRENT_TIMESTAMP + INTERVAL '1 hour',
                          CURRENT_TIMESTAMP - INTERVAL '1 day',
                          CURRENT_TIMESTAMP - INTERVAL '1 minute')
                RETURNING id
                """,
                Long.class,
                sellerId
        );
        optionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity)
                VALUES (?, 129000, 5)
                RETURNING id
                """,
                Long.class,
                dropId
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM stock_reservations WHERE order_item_id IN " +
                "(SELECT id FROM order_items WHERE drop_id = ?)", dropId);
        jdbcTemplate.update("DELETE FROM order_items WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM orders WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drop_options WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drops WHERE id = ?", dropId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
    }

    @Test
    @DisplayName("주문·항목·HELD 예약과 재고 선점을 한 트랜잭션에서 생성한다")
    void createsOrderAndReservation() {
        // when
        Order order = createOrder(idempotencyKey(), 2);

        // then
        assertThat(order.getItemsAmount()).isEqualTo(258000);
        assertThat(order.getTotalAmount()).isEqualTo(261000);
        assertThat(queryInt("SELECT reserved_quantity FROM drop_options WHERE id = ?", optionId)).isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM order_items WHERE order_id = ?", order.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT sr.status
                FROM stock_reservations sr
                JOIN order_items oi ON oi.id = sr.order_item_id
                WHERE oi.order_id = ?
                """,
                String.class,
                order.getId()
        )).isEqualTo("HELD");
    }

    @Test
    @DisplayName("재고 부족이면 주문·항목·예약과 재고 변경을 모두 롤백한다")
    void rollsBackWhenStockIsInsufficient() {
        // when & then
        assertThatThrownBy(() -> createOrder(idempotencyKey(), 6))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK));
        assertThat(queryInt("SELECT reserved_quantity FROM drop_options WHERE id = ?", optionId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM orders WHERE drop_id = ?", dropId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM order_items WHERE drop_id = ?", dropId)).isZero();
    }

    @Test
    @DisplayName("재고 1개에 동시 주문이 들어와도 한 건만 선점한다")
    void preventsOversellingForConcurrentOrders() throws Exception {
        // given
        jdbcTemplate.update("UPDATE drop_options SET total_quantity = 1 WHERE id = ?", optionId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> tryCreateConcurrently(ready, start, UUID.randomUUID().toString())),
                    executor.submit(() -> tryCreateConcurrently(ready, start, UUID.randomUUID().toString()))
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
            assertThat(queryInt("SELECT reserved_quantity FROM drop_options WHERE id = ?", optionId)).isEqualTo(1);
            assertThat(queryInt("SELECT COUNT(*) FROM orders WHERE drop_id = ?", dropId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean tryCreateConcurrently(CountDownLatch ready, CountDownLatch start, String key)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            createOrder(key, 1);
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);
            return false;
        }
    }

    private Order createOrder(String key, int quantity) {
        return transactionService.create(
                buyerId,
                IdempotencyKey.from(key),
                RequestHash.from("a".repeat(64)),
                new OrderCreateRequest(
                        dropId,
                        List.of(new OrderCreateRequest.Item(optionId, quantity)),
                        new OrderCreateRequest.ShippingAddress(
                                "홍길동", "01012345678", "06236", "서울시 강남구", "101호", null
                        )
                )
        );
    }

    private int queryInt(String sql, Object parameter) {
        return jdbcTemplate.queryForObject(sql, Integer.class, parameter);
    }

    private String idempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
