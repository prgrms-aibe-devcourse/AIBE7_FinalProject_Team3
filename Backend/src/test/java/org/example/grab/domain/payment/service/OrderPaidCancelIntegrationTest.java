package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 결제 후 주문 취소(ORDER.md 1.4, ERD.md 3.3)가 PG 결제 취소 결과에 따라 결제·주문·예약·재고를 한 번만 바꾸는지 확인한다.
@SpringBootTest
class OrderPaidCancelIntegrationTest {

    private static final OrderCancelRequest REQUEST = new OrderCancelRequest("단순 변심");
    private static final OffsetDateTime PG_CANCELED_AT = OffsetDateTime.parse("2026-10-06T05:10:00Z");

    @Autowired
    private OrderCancelService orderCancelService;

    @Autowired
    private PaymentTransactionService transactionService;

    @Autowired
    private SellerOrderService sellerOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private PaymentTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("결제 완료 주문은 PG 전액 취소에 성공하면 결제·주문을 취소하고 판매 수량을 가용 재고로 돌려준다")
    void cancelPaidOrder() {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any())).willReturn(canceled());
        String clientKey = newKey();

        // when
        OrderCancelResponse response = cancel(paid.order(), clientKey);

        // then
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(response.canceledAt()).isNotNull();
        assertThat(orderRow(paid.order())).containsEntry("status", "CANCELED").containsEntry("cancel_reason", "단순 변심");
        assertThat(paymentRow(paid.paymentId())).containsEntry("status", "CANCELED");
        assertThat(paymentRow(paid.paymentId()).get("canceled_at")).isNotNull();
        Map<String, Object> cancellation = cancellationRow(paid.paymentId());
        assertThat(cancellation)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("purpose", "ORDER_CANCEL")
                .containsEntry("provider_cancel_id", "cancel-transaction-key")
                .containsEntry("attempt_count", 1)
                .containsEntry("amount", PaymentTestFixture.TOTAL_AMOUNT)
                .containsEntry("requested_by", fixture.buyerId());
        assertThat(reservationRow(paid.order()))
                .containsEntry("status", "RELEASED")
                .containsEntry("release_reason", "ORDER_CANCELED")
                .containsEntry("release_destination", "AVAILABLE");
        assertThat(reservationRow(paid.order()).get("committed_at")).isNotNull();
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);

        // PG에는 서버가 만든 멱등 키로 전액 취소를 요청한다. 클라이언트 키를 흘려보내지 않는다.
        PaymentCancelCommand command = capturedCommands(1).get(0);
        assertThat(command.paymentKey()).isEqualTo(paid.paymentKey());
        assertThat(command.cancelReason()).isEqualTo("단순 변심");
        assertThat(command.idempotencyKey()).isEqualTo(cancellation.get("idempotency_key")).isNotEqualTo(clientKey);
    }

    @Test
    @DisplayName("배송 준비 중인 주문도 취소하고, 끝난 취소를 같은 키로 재전송하면 PG를 다시 부르지 않고 최초 결과를 돌려준다")
    void cancelPreparingOrderAndReplay() {
        // given
        PaidOrder paid = paidOrder();
        jdbcTemplate.update("UPDATE orders SET status = 'PREPARING' WHERE id = ?", paid.order().getId());
        given(paymentGateway.cancel(any())).willReturn(canceled());
        String key = newKey();

        // when
        OrderCancelResponse first = cancel(paid.order(), key);
        OrderCancelResponse replay = cancel(paid.order(), key);

        // then
        assertThat(first.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(replay).isEqualTo(first);
        verify(paymentGateway, times(1)).cancel(any());
        assertThat(fixture.quantities()).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("PG가 결제 취소를 거절하면 주문을 바꾸지 않고 PAYMENT_CANCEL_FAILED로 응답하며, 새 키로 다시 취소할 수 있다")
    void rejectedCancel() {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."))
                .willReturn(canceled());

        // when & then: 거절
        assertThatThrownBy(() -> cancel(paid.order(), newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
        assertThat(orderRow(paid.order()))
                .containsEntry("status", "PAID")
                .containsEntry("cancel_idempotency_key", null)
                .containsEntry("cancel_reason", null);
        assertThat(paymentRow(paid.paymentId())).containsEntry("status", "SUCCEEDED");
        assertThat(cancellationRow(paid.paymentId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", "NOT_CANCELABLE_PAYMENT");
        assertThat(fixture.quantities()).containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);

        // when & then: 새 키로 다시 요청하면 새 취소 요청으로 처리한다
        assertThat(cancel(paid.order(), newKey()).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.quantities()).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("PG 취소 결과를 모르면 주문을 바꾸지 않고 UNKNOWN으로 응답하며, 그동안 배송 처리와 다른 키의 취소를 막고 같은 키 재전송으로 확정한다")
    void unknownCancelResolvedByResend() {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.unknown(null, null, "결제 취소 응답을 확인하지 못했습니다."))
                .willReturn(canceled());
        String key = newKey();

        // when: 결과 불명
        OrderCancelResponse unknown = cancel(paid.order(), key);

        // then
        assertThat(unknown.status()).isEqualTo(OrderStatus.PAID);
        assertThat(unknown.refundStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(unknown.canceledAt()).isNull();
        Map<String, Object> pending = cancellationRow(paid.paymentId());
        assertThat(pending).containsEntry("status", "UNKNOWN").containsEntry("attempt_count", 1);
        assertThat(pending.get("next_retry_at")).isNotNull();
        assertThat(fixture.quantities()).containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
        assertThatThrownBy(() -> sellerOrderService.prepareShipment(fixture.sellerId(), paid.order().getUuid()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PAYMENT_CANCELLATION_UNKNOWN);
        assertThatThrownBy(() -> cancel(paid.order(), newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.ORDER_STATUS_CONFLICT);

        // when: 같은 키로 재전송
        OrderCancelResponse resolved = cancel(paid.order(), key);

        // then: 같은 서버 멱등 키로 다시 요청해 확정하고, 취소 요청은 하나만 남는다
        assertThat(resolved.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(resolved.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        List<PaymentCancelCommand> commands = capturedCommands(2);
        assertThat(commands.get(1).idempotencyKey()).isEqualTo(commands.get(0).idempotencyKey());
        assertThat(cancellationRow(paid.paymentId())).containsEntry("status", "SUCCEEDED").containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_cancellations WHERE payment_id = ?", Integer.class, paid.paymentId()))
                .isEqualTo(1);
        assertThat(fixture.quantities()).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("발송된 주문은 PG를 부르지 않고 ORDER_NOT_CANCELABLE로 거부한다")
    void rejectsShippedOrder() {
        // given
        PaidOrder paid = paidOrder();
        jdbcTemplate.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", paid.order().getId());

        // when & then
        assertThatThrownBy(() -> cancel(paid.order(), newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_CANCELABLE);
        verify(paymentGateway, never()).cancel(any());
    }

    private OrderCancelResponse cancel(Order order, String key) {
        return orderCancelService.cancel(fixture.buyerId(), order.getUuid(), key, REQUEST);
    }

    // 결제 승인까지 끝나 주문이 PAID, 예약이 COMMITTED, 선점 수량이 판매 수량으로 옮겨진 상태를 만든다.
    private PaidOrder paidOrder() {
        Order order = fixture.createOrder();
        String paymentKey = "payment-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(newKey()), RequestHash.from("c".repeat(64)),
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT), now);
        transactionService.applyResult(ready.paymentId(), PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now), null, now);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        return new PaidOrder(order, ready.paymentId(), paymentKey);
    }

    private static PaymentCancelResult canceled() {
        return PaymentCancelResult.canceled("CANCELED", "cancel-transaction-key", PG_CANCELED_AT);
    }

    private List<PaymentCancelCommand> capturedCommands(int count) {
        ArgumentCaptor<PaymentCancelCommand> captor = ArgumentCaptor.forClass(PaymentCancelCommand.class);
        verify(paymentGateway, times(count)).cancel(captor.capture());
        return captor.getAllValues();
    }

    private Map<String, Object> orderRow(Order order) {
        return jdbcTemplate.queryForMap(
                "SELECT status, cancel_reason, cancel_idempotency_key FROM orders WHERE id = ?", order.getId());
    }

    private Map<String, Object> paymentRow(Long paymentId) {
        return jdbcTemplate.queryForMap("SELECT status, canceled_at FROM payments WHERE id = ?", paymentId);
    }

    private Map<String, Object> cancellationRow(Long paymentId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, purpose, provider_cancel_id, attempt_count, amount, requested_by, idempotency_key,
                       failure_code, next_retry_at
                FROM payment_cancellations WHERE payment_id = ? ORDER BY id DESC LIMIT 1
                """, paymentId);
    }

    private Map<String, Object> reservationRow(Order order) {
        return jdbcTemplate.queryForMap("""
                SELECT r.status, r.release_reason, r.release_destination, r.committed_at
                FROM stock_reservations r JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ?
                """, order.getId());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private record PaidOrder(Order order, Long paymentId, String paymentKey) {
    }
}
