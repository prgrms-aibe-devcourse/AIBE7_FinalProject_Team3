package org.example.grab.domain.order.service;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.dto.PaymentCompletionResult;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ReleaseDestination;
import org.example.grab.domain.order.entity.ReleaseReason;
import org.example.grab.domain.order.entity.ReservationStatus;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest
class OrderPaymentServiceTest {

    @Autowired
    private OrderPaymentService orderPaymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long buyerId;
    private Long dropId;
    private Long firstOptionId;
    private Long secondOptionId;
    private OffsetDateTime expiresAt;
    private Order order;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        buyerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "o" + uniqueValue.substring(0, 8));
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB 판매자', ?) RETURNING id",
                Long.class, buyerId, "seller-" + uniqueValue + "@example.com");
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id", Long.class, sellerId);
        // 주문 생성 트랜잭션이 이미 선점을 반영한 상태: 첫 옵션 2개, 둘째 옵션 1개
        firstOptionId = insertOption(2);
        secondOptionId = insertOption(1);

        expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).truncatedTo(ChronoUnit.MICROS);
        order = orderRepository.saveAndFlush(Order.create(
                "ORD-PAY-" + uniqueValue, buyerId, dropId, "order-key-" + uniqueValue, "a".repeat(64),
                "한정판 후드", "GRAB 판매자", 45000, 3000,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", null),
                expiresAt));
        // 옵션 ID 역순으로 저장해도 확정·만료는 옵션 ID 순서로 처리하는지 함께 확인한다.
        holdItem(secondOptionId, 1);
        holdItem(firstOptionId, 2);
        entityManager.clear();
    }

    @Test
    @DisplayName("결제 요청 잠금은 본인 주문을 돌려주고 다른 구매자의 주문은 ORDER_NOT_FOUND로 거부한다")
    void lockForPaymentRequest() {
        // when
        PayableOrder payable = orderPaymentService.lockForPaymentRequest(buyerId, order.getUuid());

        // then
        assertThat(payable.id()).isEqualTo(order.getId());
        assertThat(payable.orderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(payable.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(payable.totalAmount()).isEqualTo(48000);
        assertThatThrownBy(() -> orderPaymentService.lockForPaymentRequest(buyerId + 1, order.getUuid()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("결제 확정은 주문 PAID, 예약 COMMITTED, 선점 수량을 판매 수량으로 옮긴다")
    void completePayment() {
        // given
        OffsetDateTime approvedAt = expiresAt.minusMinutes(10);

        // when
        PaymentCompletionResult result = orderPaymentService.completePayment(
                lockedOrder(), 48000, approvedAt, approvedAt.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(result).isEqualTo(PaymentCompletionResult.COMPLETED);
        Order found = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(found.getPaidAt()).isEqualTo(approvedAt);
        assertThat(reservations()).extracting(StockReservation::getStatus)
                .containsOnly(ReservationStatus.COMMITTED);
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 2);
        assertThat(quantities(secondOptionId)).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 1);
    }

    @Test
    @DisplayName("결제 마감이 지났거나 금액이 다르거나 결제 대기가 아니면 주문·예약·재고를 바꾸지 않는다")
    void doesNotCompleteWhenNotPayable() {
        // when
        PaymentCompletionResult expired = orderPaymentService.completePayment(
                lockedOrder(), 48000, expiresAt, expiresAt);
        PaymentCompletionResult mismatch = orderPaymentService.completePayment(
                lockedOrder(), 47000, expiresAt.minusMinutes(1), expiresAt.minusMinutes(1));
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELED', canceled_at = CURRENT_TIMESTAMP WHERE id = ?",
                order.getId());
        entityManager.clear();
        PaymentCompletionResult canceled = orderPaymentService.completePayment(
                lockedOrder(), 48000, expiresAt.minusMinutes(1), expiresAt.minusMinutes(1));
        entityManager.flush();

        // then
        assertThat(expired).isEqualTo(PaymentCompletionResult.EXPIRED);
        assertThat(mismatch).isEqualTo(PaymentCompletionResult.AMOUNT_MISMATCH);
        assertThat(canceled).isEqualTo(PaymentCompletionResult.NOT_PAYABLE);
        assertThat(reservations()).extracting(StockReservation::getStatus).containsOnly(ReservationStatus.HELD);
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 2).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("한 옵션이라도 선점 수량이 예약보다 적으면 다른 옵션도 확정하지 않고 INVENTORY_INCONSISTENT를 돌려준다")
    void doesNotCompleteWhenReservedQuantityIsShort() {
        // given: 뒤쪽 옵션(secondOptionId)의 선점 수량만 어긋나 있다
        jdbcTemplate.update("UPDATE drop_options SET reserved_quantity = 0 WHERE id = ?", secondOptionId);
        OffsetDateTime approvedAt = expiresAt.minusMinutes(10);

        // when
        PaymentCompletionResult result = orderPaymentService.completePayment(
                lockedOrder(), 48000, approvedAt, approvedAt.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // then: 예외로 트랜잭션을 되돌리지 않고, 앞쪽 옵션도 판매 수량으로 옮기지 않는다
        assertThat(result).isEqualTo(PaymentCompletionResult.INVENTORY_INCONSISTENT);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservations()).extracting(StockReservation::getStatus).containsOnly(ReservationStatus.HELD);
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 2).containsEntry("sold_quantity", 0);
        assertThat(quantities(secondOptionId)).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("예약이 이미 해제됐거나 주문 항목보다 적으면 예외 없이 INVENTORY_INCONSISTENT를 돌려주고 아무것도 바꾸지 않는다")
    void doesNotCompleteWhenReservationIsInconsistent() {
        // given
        OffsetDateTime approvedAt = expiresAt.minusMinutes(10);
        Long secondReservationId = jdbcTemplate.queryForObject("""
                SELECT r.id FROM stock_reservations r JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ? AND i.option_id = ?
                """, Long.class, order.getId(), secondOptionId);

        // when: 한 예약이 이미 해제된 상태
        jdbcTemplate.update("""
                UPDATE stock_reservations
                SET status = 'RELEASED', released_at = CURRENT_TIMESTAMP,
                    release_reason = 'EXPIRED', release_destination = 'AVAILABLE'
                WHERE id = ?
                """, secondReservationId);
        PaymentCompletionResult released = orderPaymentService.completePayment(
                lockedOrder(), 48000, approvedAt, approvedAt.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();
        // when: 한 예약이 아예 없는 상태
        jdbcTemplate.update("DELETE FROM stock_reservations WHERE id = ?", secondReservationId);
        PaymentCompletionResult missing = orderPaymentService.completePayment(
                lockedOrder(), 48000, approvedAt, approvedAt.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // then: 남은 예약도 확정하지 않는다
        assertThat(released).isEqualTo(PaymentCompletionResult.INVENTORY_INCONSISTENT);
        assertThat(missing).isEqualTo(PaymentCompletionResult.INVENTORY_INCONSISTENT);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservations()).extracting(StockReservation::getStatus).containsOnly(ReservationStatus.HELD);
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 2).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("결제 마감이 지난 결제 대기 주문을 만료하고 선점 재고를 가용 재고로 되돌린다")
    void expireIfDue() {
        // when
        boolean expired = orderPaymentService.expireIfDue(order.getId(), expiresAt);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(expired).isTrue();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reservations()).allSatisfy(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(reservation.getReleaseReason()).isEqualTo(ReleaseReason.EXPIRED);
            assertThat(reservation.getReleaseDestination()).isEqualTo(ReleaseDestination.AVAILABLE);
        });
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
        assertThat(quantities(secondOptionId)).containsEntry("reserved_quantity", 0);
    }

    @Test
    @DisplayName("마감 전이거나 이미 결제된 주문은 만료하지 않는다")
    void doesNotExpireWhenNotDue() {
        // given
        OffsetDateTime approvedAt = expiresAt.minusMinutes(5);

        // when
        boolean beforeDeadline = orderPaymentService.expireIfDue(order.getId(), expiresAt.minusSeconds(1));
        orderPaymentService.completePayment(lockedOrder(), 48000, approvedAt, approvedAt);
        boolean afterPaid = orderPaymentService.expireIfDue(order.getId(), expiresAt.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(beforeDeadline).isFalse();
        assertThat(afterPaid).isFalse();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(quantities(firstOptionId)).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 2);
    }

    private Long insertOption(int reservedQuantity) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity)
                VALUES (?, 15000, 10, ?)
                RETURNING id
                """,
                Long.class, dropId, reservedQuantity);
    }

    private void holdItem(Long optionId, int quantity) {
        OrderItem item = orderItemRepository.saveAndFlush(OrderItem.create(order, optionId, "옵션", 15000, quantity));
        stockReservationRepository.saveAndFlush(StockReservation.hold(item, expiresAt));
    }

    // 결제 확정은 결제 결과 반영처럼 같은 트랜잭션에서 주문을 먼저 잠근 뒤 호출한다.
    private PayableOrder lockedOrder() {
        return orderPaymentService.lockForPaymentResult(order.getId());
    }

    private List<StockReservation> reservations() {
        return stockReservationRepository.findAllOfOrderSortedByOption(order.getId());
    }

    private Map<String, Object> quantities(Long optionId) {
        return jdbcTemplate.queryForMap(
                "SELECT reserved_quantity, sold_quantity FROM drop_options WHERE id = ?", optionId);
    }
}
