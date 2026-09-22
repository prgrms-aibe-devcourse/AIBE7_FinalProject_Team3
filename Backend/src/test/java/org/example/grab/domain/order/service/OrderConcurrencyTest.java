package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCancelRequest;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OrderCancellationService orderCancellationService;

    @Autowired
    private SellerOrderService sellerOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("마지막 재고에 동시 주문이 들어와도 한 주문만 재고를 선점한다")
    void preventsOversellingForConcurrentOrders() throws Exception {
        // given
        Fixture fixture = createFixture();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        OrderCreateRequest request = createRequest(fixture.dropId(), fixture.optionId());

        try {
            Future<Result> first = executor.submit(() -> createConcurrently(
                    fixture.firstBuyerId(), request, ready, start
            ));
            Future<Result> second = executor.submit(() -> createConcurrently(
                    fixture.secondBuyerId(), request, ready, start
            ));
            ready.await();

            // when
            start.countDown();
            List<Result> results = List.of(first.get(), second.get());

            // then
            assertThat(results).filteredOn(Result::success).hasSize(1);
            assertThat(results).filteredOn(result -> result.errorCode() == ErrorCode.INSUFFICIENT_STOCK)
                    .hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT reserved_quantity FROM drop_options WHERE id = ?",
                    Integer.class,
                    fixture.optionId()
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE drop_id = ?",
                    Integer.class,
                    fixture.dropId()
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    @DisplayName("주문 취소와 배송 준비가 동시에 요청되면 하나의 상태 전이만 성공한다")
    void allowsOnlyOneOfCancellationAndShipmentPreparation() throws Exception {
        // given
        Fixture fixture = createFixture();
        var created = orderCreationService.create(
                fixture.firstBuyerId(), UUID.randomUUID().toString(),
                createRequest(fixture.dropId(), fixture.optionId())
        );
        markPaid(created.orderId(), fixture.optionId());
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> cancel = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    orderCancellationService.cancel(
                            fixture.firstBuyerId(), created.orderId(), UUID.randomUUID().toString(),
                            new OrderCancelRequest("동시 취소")
                    );
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            Future<Boolean> prepare = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    sellerOrderService.prepareShipment(fixture.sellerUserId(), created.orderId());
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
            ready.await();

            // when
            start.countDown();

            // then
            assertThat(List.of(cancel.get(), prepare.get())).filteredOn(Boolean::booleanValue).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM orders WHERE id = ?", String.class, created.orderId()
            )).isIn("CANCELED", "PREPARING");
        } finally {
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private Result createConcurrently(
            Long buyerId,
            OrderCreateRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            orderCreationService.create(buyerId, UUID.randomUUID().toString(), request);
            return new Result(true, null);
        } catch (BusinessException exception) {
            return new Result(false, exception.getErrorCode());
        }
    }

    private OrderCreateRequest createRequest(Long dropId, Long optionId) {
        return new OrderCreateRequest(
                dropId,
                List.of(new OrderCreateRequest.OrderItemRequest(optionId, 1)),
                new OrderCreateRequest.ShippingAddressRequest(
                        "홍길동", "01012345678", "06236", "서울시", null, null
                )
        );
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString();
        Long firstBuyerId = createUser("concurrent-1-" + suffix + "@example.com", "구매자1");
        Long secondBuyerId = createUser("concurrent-2-" + suffix + "@example.com", "구매자2");
        Long sellerUserId = createUser("concurrent-seller-" + suffix + "@example.com", "판매자");
        Long sellerId = jdbcTemplate.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '동시성 브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerUserId, "concurrent-seller-" + suffix + "@example.com", sellerUserId);
        Long categoryId = jdbcTemplate.queryForObject("""
                INSERT INTO categories (code, name) VALUES (?, '동시성 카테고리') RETURNING id
                """, Long.class, "concurrent-" + suffix);
        Long dropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (
                    seller_id, category_id, status, name, description, shipping_fee,
                    shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at
                ) VALUES (?, ?, 'GRAB', '동시성 상품', '설명', 0, '안내',
                          CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerId, categoryId);
        Long optionId = jdbcTemplate.queryForObject("""
                INSERT INTO drop_options (drop_id, unit_price, total_quantity)
                VALUES (?, 1000, 1) RETURNING id
                """, Long.class, dropId);
        return new Fixture(firstBuyerId, secondBuyerId, sellerUserId, sellerId, categoryId, dropId, optionId);
    }

    private Long createUser(String email, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, phone, role, status, provider)
                VALUES (?, 'encoded-password', ?, '01000000000', 'USER', 'ACTIVE', 'LOCAL')
                RETURNING id
                """, Long.class, email, name);
    }

    private void markPaid(Long orderId, Long optionId) {
        jdbcTemplate.update("UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP WHERE id = ?", orderId);
        jdbcTemplate.update("""
                UPDATE stock_reservations SET status = 'COMMITTED', committed_at = CURRENT_TIMESTAMP
                WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id = ?)
                """, orderId);
        jdbcTemplate.update(
                "UPDATE drop_options SET reserved_quantity = 0, sold_quantity = 1 WHERE id = ?", optionId
        );
        jdbcTemplate.update("""
                INSERT INTO payments (
                    order_id, provider, idempotency_key, request_hash, provider_payment_id,
                    amount, status, reconciliation_status, approved_at
                ) VALUES (?, 'MOCK', ?, ?, ?, 1000, 'SUCCEEDED', 'NONE', CURRENT_TIMESTAMP)
                """, orderId, UUID.randomUUID().toString(), "d".repeat(64), "payment-" + UUID.randomUUID());
    }

    private void deleteFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM payment_cancellations WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE order_id IN (SELECT id FROM orders WHERE drop_id = ?))",
                fixture.dropId());
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN "
                + "(SELECT id FROM orders WHERE drop_id = ?)", fixture.dropId());
        jdbcTemplate.update("DELETE FROM order_cancellation_requests WHERE order_id IN "
                + "(SELECT id FROM orders WHERE drop_id = ?)", fixture.dropId());
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN "
                + "(SELECT id FROM orders WHERE drop_id = ?)", fixture.dropId());
        jdbcTemplate.update("DELETE FROM stock_reservations WHERE order_item_id IN "
                + "(SELECT id FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE drop_id = ?))",
                fixture.dropId());
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE drop_id = ?)", fixture.dropId());
        jdbcTemplate.update("DELETE FROM orders WHERE drop_id = ?", fixture.dropId());
        jdbcTemplate.update("DELETE FROM drop_options WHERE drop_id = ?", fixture.dropId());
        jdbcTemplate.update("DELETE FROM drops WHERE id = ?", fixture.dropId());
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", fixture.categoryId());
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", fixture.sellerId());
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", fixture.firstBuyerId(),
                fixture.secondBuyerId(), fixture.sellerUserId());
    }

    private record Result(boolean success, ErrorCode errorCode) {
    }

    private record Fixture(
            Long firstBuyerId,
            Long secondBuyerId,
            Long sellerUserId,
            Long sellerId,
            Long categoryId,
            Long dropId,
            Long optionId
    ) {
    }
}
