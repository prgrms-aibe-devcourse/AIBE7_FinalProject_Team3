package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
    결제 통합 테스트용 주문 데이터. 주문 생성 트랜잭션이 이미 끝난 상태(옵션 선점 2개, HELD 예약)를 만든다.
    서비스 트랜잭션이 실제로 커밋되는 테스트에서 쓰므로 만든 데이터는 cleanup()으로 직접 지운다.
 */
class PaymentTestFixture {

    static final long TOTAL_AMOUNT = 33000;
    static final int QUANTITY = 2;

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockReservationRepository stockReservationRepository;

    private final Long buyerId;
    private final Long sellerId;
    private final Long dropId;
    private final Long optionId;

    PaymentTestFixture(
            JdbcTemplate jdbcTemplate,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            StockReservationRepository stockReservationRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockReservationRepository = stockReservationRepository;

        String uniqueValue = UUID.randomUUID().toString();
        this.buyerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "b" + uniqueValue.substring(0, 8));
        this.sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB 판매자', ?) RETURNING id",
                Long.class, buyerId, "seller-" + uniqueValue + "@example.com");
        this.dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id", Long.class, sellerId);
        // 반복 테스트에서 주문을 여러 개 만들 수 있도록 선점 수량은 주문을 만들 때마다 더한다.
        this.optionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity)
                VALUES (?, 15000, 1000, 0)
                RETURNING id
                """,
                Long.class, dropId);
    }

    Order createOrder() {
        String uniqueValue = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).truncatedTo(ChronoUnit.MICROS);
        Order order = orderRepository.saveAndFlush(Order.create(
                "ORD-PAY-" + uniqueValue, buyerId, dropId, "order-key-" + uniqueValue, "a".repeat(64),
                "한정판 후드", "GRAB 판매자", 30000, 3000,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", null),
                expiresAt));
        OrderItem item = orderItemRepository.saveAndFlush(OrderItem.create(order, optionId, "검정 / L", 15000, QUANTITY));
        stockReservationRepository.saveAndFlush(StockReservation.hold(item, expiresAt));
        jdbcTemplate.update("UPDATE drop_options SET reserved_quantity = reserved_quantity + ? WHERE id = ?",
                QUANTITY, optionId);
        return order;
    }

    void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM payment_cancellations WHERE payment_id IN
                    (SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id WHERE o.drop_id = ?)
                """, dropId);
        jdbcTemplate.update("""
                DELETE FROM payment_events WHERE payment_id IN
                    (SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id WHERE o.drop_id = ?)
                """, dropId);
        jdbcTemplate.update(
                "DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE drop_id = ?)", dropId);
        jdbcTemplate.update("DELETE FROM stock_reservations WHERE order_item_id IN "
                + "(SELECT id FROM order_items WHERE drop_id = ?)", dropId);
        jdbcTemplate.update("DELETE FROM order_items WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM orders WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drop_options WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drops WHERE id = ?", dropId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
    }

    Long buyerId() {
        return buyerId;
    }

    Long sellerId() {
        return sellerId;
    }

    Long optionId() {
        return optionId;
    }

    OrderStatus orderStatus(Order order) {
        return OrderStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, order.getId()));
    }

    String reservationStatus(Order order) {
        return jdbcTemplate.queryForObject("""
                SELECT r.status FROM stock_reservations r JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ?
                """, String.class, order.getId());
    }

    Map<String, Object> quantities() {
        return jdbcTemplate.queryForMap(
                "SELECT reserved_quantity, sold_quantity FROM drop_options WHERE id = ?", optionId);
    }

    List<Map<String, Object>> payments(Order order) {
        return jdbcTemplate.queryForList(
                "SELECT status, reconciliation_status FROM payments WHERE order_id = ? ORDER BY id", order.getId());
    }
}
