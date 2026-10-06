package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.idempotency.RequestHashGenerator;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
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

// PG는 승인했지만 주문을 확정할 수 없는 경우와, 결과가 확정되지 않은 이전 결제를 정리하는 경우를 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentReconciliationIntegrationTest {

    @Autowired
    private PaymentTransactionService transactionService;

    @Autowired
    private RequestHashGenerator requestHashGenerator;

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
    private Order order;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
        order = fixture.createOrder();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("PG 승인 도중 결제 마감이 지나면 주문을 확정하지 않고 결제를 SUCCEEDED·보정 필요로 남긴다")
    void approvalAfterDeadline() {
        // given
        OffsetDateTime beforeDeadline = order.getPaymentExpiresAt().minusSeconds(5);
        PaymentPreparation.Ready ready = prepare("payment-late", beforeDeadline);
        PaymentGatewayResult approved = PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, beforeDeadline);

        // when
        transactionService.applyResult(ready.paymentId(), approved, null, order.getPaymentExpiresAt());

        // then
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertUnchangedOrder();
    }

    @Test
    @DisplayName("PG 승인 응답의 주문번호나 금액이 서버 주문과 다르면 주문을 확정하지 않고 보정 필요로 남긴다")
    void approvalWithMismatchedResponse() {
        // given: 금액이 다른 승인 응답과 주문번호가 다른 승인 응답을 각각 다른 주문에 반영한다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order otherOrder = fixture.createOrder();
        PaymentPreparation.Ready wrongAmount = prepare(order, "payment-amount", now);
        PaymentPreparation.Ready wrongOrder = prepare(otherOrder, "payment-order", now);

        // when
        transactionService.applyResult(wrongAmount.paymentId(), PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT - 1000, now), null, now);
        transactionService.applyResult(wrongOrder.paymentId(), PaymentGatewayResult.approved(
                "DONE", "ORD-OTHER-000001", PaymentTestFixture.TOTAL_AMOUNT, now), null, now);

        // then
        for (Order target : List.of(order, otherOrder)) {
            assertThat(fixture.payments(target)).singleElement().satisfies(payment -> {
                assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
                assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
            });
            assertThat(fixture.orderStatus(target)).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(fixture.reservationStatus(target)).isEqualTo("HELD");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT reconciliation_reason FROM payments WHERE order_id = ?", String.class, target.getId()))
                    .contains("불일치");
        }
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY * 2)
                .containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("승인됐지만 주문을 확정하지 못한 결제가 있는 주문은 새 결제를 PG 호출 없이 거부한다")
    void paymentAfterUnconfirmedApprovalIsRejected() {
        // given: 첫 결제는 PG가 승인했지만 금액이 달라 주문을 확정하지 못하고 보정 대상으로 남았다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        given(paymentGateway.confirm(any())).willReturn(PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT - 1000, now));
        PaymentResponse first = payAt(now, "payment-first");
        assertThat(first.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);

        // when & then: 사용자가 결제창을 다시 열어 요청해도 이미 돈이 나간 주문이므로 새 승인을 요청하지 않는다
        assertThatThrownBy(() -> payAt(now.plusSeconds(5), "payment-second"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        verify(paymentGateway, times(1)).confirm(any());
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
    }

    @Test
    @DisplayName("승인은 됐지만 선점 재고가 어긋나 있으면 승인 기록을 남긴 채 주문을 확정하지 않고 보정 필요로 남긴다")
    void approvalWithInconsistentInventory() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PaymentPreparation.Ready ready = prepare("payment-inventory", now);
        jdbcTemplate.update("UPDATE drop_options SET reserved_quantity = ? WHERE id = ?",
                PaymentTestFixture.QUANTITY - 1, fixture.optionId());
        PaymentGatewayResult approved = PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now);

        // when
        transactionService.applyResult(ready.paymentId(), approved, null, now);

        // then: 결제는 롤백되지 않고 SUCCEEDED·보정 필요로 남아, 이후 조회로 다시 정리할 대상이 되지 않는다
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reconciliation_reason FROM payments WHERE id = ?", String.class, ready.paymentId()))
                .contains("재고");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_events WHERE payment_id = ?", Integer.class, ready.paymentId()))
                .isEqualTo(1);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.reservationStatus(order)).isEqualTo("HELD");
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY - 1)
                .containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("이전 결제가 PENDING에 머물면 60초 전에는 새 결제를 거부하고, 지나면 조회로 정리한 뒤 새 결제를 진행한다")
    void stalePendingIsResolvedByLookup() {
        // given: 서버가 PG 승인 요청 전에 멈춰 PENDING만 남은 상황
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        prepare("payment-stuck", now);
        given(paymentGateway.lookup("payment-stuck")).willReturn(
                PaymentGatewayResult.notApproved(null, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));
        given(paymentGateway.confirm(any())).willReturn(PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now));

        // when & then: 아직 승인 요청 중일 수 있는 PENDING은 조회하지 않고 거부한다
        assertThatThrownBy(() -> payAt(now.plusSeconds(30), "payment-new"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        verify(paymentGateway, never()).lookup(any());

        // when & then: 충분히 오래된 PENDING은 조회로 FAILED 확정 후 새 결제를 진행한다
        PaymentResponse response = payAt(now.plus(PaymentTransactionService.STALE_PENDING_AFTER).plusSeconds(1),
                "payment-new");
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        List<Map<String, Object>> payments = fixture.payments(order);
        assertThat(payments).extracting(payment -> payment.get("status")).containsExactly("FAILED", "SUCCEEDED");
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
    }

    private PaymentPreparation.Ready prepare(String paymentKey, OffsetDateTime now) {
        return prepare(order, paymentKey, now);
    }

    private PaymentPreparation.Ready prepare(Order target, String paymentKey, OffsetDateTime now) {
        return (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), target.getUuid(), IdempotencyKey.from(UUID.randomUUID().toString()),
                RequestHash.from("d".repeat(64)), new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT), now);
    }

    // 결제 요청 시각을 고정한 PaymentService로 요청한다.
    private PaymentResponse payAt(OffsetDateTime requestTime, String paymentKey) {
        return payAt(requestTime, paymentKey, UUID.randomUUID().toString());
    }

    private PaymentResponse payAt(OffsetDateTime requestTime, String paymentKey, String idempotencyKey) {
        Clock clock = Clock.fixed(requestTime.toInstant(), ZoneOffset.UTC);
        PaymentService service = new PaymentService(paymentGateway, transactionService, requestHashGenerator, clock);
        return service.pay(fixture.buyerId(), order.getUuid(), idempotencyKey,
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT));
    }

    @Test
    @DisplayName("승인 요청이 PG에 닿지 않아 UNKNOWN이 되면, 다음 요청이 같은 서버 멱등 키로 승인을 다시 요청해 확정한다")
    void confirmNotReachingPgIsRetriedWithSameKey() {
        // given: 첫 승인 요청은 연결 실패로 결과 불명이고, 조회해 보니 인증만 끝난 상태다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다."))
                .willReturn(PaymentGatewayResult.approved(
                        "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now));
        given(paymentGateway.lookup("payment-first"))
                .willReturn(PaymentGatewayResult.awaitingConfirmation("IN_PROGRESS"));
        PaymentResponse first = payAt(now, "payment-first");
        assertThat(first.status()).isEqualTo(PaymentStatus.UNKNOWN);

        // when: 사용자가 결제창을 다시 열어 새 paymentKey로 요청한다
        assertThatThrownBy(() -> payAt(now.plusSeconds(5), "payment-second"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);

        // then: 이전 결제가 같은 서버 멱등 키의 재요청으로 확정되고, 새 paymentKey로는 승인을 요청하지 않는다
        ArgumentCaptor<PaymentConfirmCommand> commands = ArgumentCaptor.forClass(PaymentConfirmCommand.class);
        verify(paymentGateway, times(2)).confirm(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.paymentKey()).isEqualTo("payment-first");
            assertThat(command.orderId()).isEqualTo(order.getOrderNumber());
        });
        String serverKey = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM payments WHERE order_id = ?", String.class, order.getId());
        assertThat(commands.getAllValues()).extracting(PaymentConfirmCommand::idempotencyKey).containsOnly(serverKey);
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            // UNKNOWN으로 보정 대상이었다가 확정됐으므로 RESOLVED로 남는다.
            assertThat(payment.get("reconciliation_status")).isEqualTo("RESOLVED");
        });
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_key FROM payment_events WHERE payment_id = (SELECT id FROM payments WHERE order_id = ?)"
                        + " AND event_type = 'CONFIRM' ORDER BY id", String.class, order.getId()))
                .satisfiesExactly(
                        key -> assertThat(key).isEqualTo("confirm"),
                        key -> assertThat(key).startsWith("confirm-retry:"));
        // 승인 재요청을 판단한 조회도 기록한다. 판단 근거가 아니었던 응답은 보정 필요로 남긴다.
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_type || ':' || processing_result FROM payment_events"
                        + " WHERE payment_id = (SELECT id FROM payments WHERE order_id = ?) ORDER BY id",
                String.class, order.getId()))
                .containsExactly(
                        "CONFIRM:RECONCILIATION_REQUIRED",
                        "LOOKUP:RECONCILIATION_REQUIRED",
                        "LOOKUP:RECONCILIATION_REQUIRED",
                        "CONFIRM:APPLIED");
    }

    @Test
    @DisplayName("결과 불명인 결제를 같은 멱등 키로 다시 요청하면 조회로 정리한 확정 결과를 돌려준다")
    void replayResolvesUnknownPayment() {
        // given: 승인 응답도, 바로 이어진 조회도 실패해 UNKNOWN으로 남았다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String idempotencyKey = UUID.randomUUID().toString();
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다."));
        given(paymentGateway.lookup("payment-unknown"))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 상태를 확인하지 못했습니다."))
                .willReturn(PaymentGatewayResult.approved(
                        "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now));
        assertThat(payAt(now, "payment-unknown", idempotencyKey).status()).isEqualTo(PaymentStatus.UNKNOWN);

        // when: 클라이언트가 같은 요청을 다시 보낸다
        PaymentResponse replay = payAt(now.plusSeconds(3), "payment-unknown", idempotencyKey);

        // then: 새 결제 시도 없이 이전 결제가 승인으로 확정된다
        assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(replay.paidAt()).isNotNull();
        verify(paymentGateway, times(1)).confirm(any());
        assertThat(fixture.payments(order)).singleElement()
                .satisfies(payment -> assertThat(payment.get("status")).isEqualTo("SUCCEEDED"));
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 요청했는데 조회로도 결과를 모르면 409가 아니라 UNKNOWN을 그대로 돌려준다")
    void replayKeepsUnknownWhenStillUnresolved() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String idempotencyKey = UUID.randomUUID().toString();
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다."));
        given(paymentGateway.lookup("payment-unknown"))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 상태를 확인하지 못했습니다."));
        payAt(now, "payment-unknown", idempotencyKey);

        // when
        PaymentResponse replay = payAt(now.plusSeconds(3), "payment-unknown", idempotencyKey);

        // then
        assertThat(replay.status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(paymentGateway, times(1)).confirm(any());
        assertThat(fixture.payments(order)).singleElement()
                .satisfies(payment -> assertThat(payment.get("status")).isEqualTo("UNKNOWN"));
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reconciliationReason()).isEqualTo("PG 승인 결과 확인 불가");
    }

    private String reconciliationReason() {
        return jdbcTemplate.queryForObject(
                "SELECT reconciliation_reason FROM payments WHERE order_id = ?", String.class, order.getId());
    }

    @Test
    @DisplayName("재전송 시점에 주문이 결제할 수 없는 상태면 승인 대기 결제에 승인을 다시 요청하지 않는다")
    void replayDoesNotConfirmForUnpayableOrder() {
        // given: 승인 요청이 PG에 닿지 않아 UNKNOWN으로 남은 뒤 주문이 취소됐다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String idempotencyKey = UUID.randomUUID().toString();
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다."))
                .willReturn(PaymentGatewayResult.approved(
                        "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now));
        given(paymentGateway.lookup("payment-unknown"))
                .willReturn(PaymentGatewayResult.awaitingConfirmation("IN_PROGRESS"));
        payAt(now, "payment-unknown", idempotencyKey);
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELED', canceled_at = CURRENT_TIMESTAMP WHERE id = ?",
                order.getId());

        // when
        PaymentResponse replay = payAt(now.plusSeconds(3), "payment-unknown", idempotencyKey);

        // then: 취소된 주문에 새로 청구하지 않고, 결과는 확인되지 않은 채로 남는다
        verify(paymentGateway, times(1)).confirm(any());
        assertThat(replay.status()).isEqualTo(PaymentStatus.UNKNOWN);
        // 운영자가 승인됐을 수 있는 결과 불명과 구분할 수 있도록 PG 상태를 사유에 남긴다.
        assertThat(reconciliationReason()).isEqualTo("PG 승인 요청 전 상태 (IN_PROGRESS)");
    }

    @Test
    @DisplayName("재전송 시점에 결제 마감이 지났으면 승인 대기 결제에 승인을 다시 요청하지 않는다")
    void replayDoesNotConfirmAfterDeadline() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String idempotencyKey = UUID.randomUUID().toString();
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다."));
        given(paymentGateway.lookup("payment-unknown"))
                .willReturn(PaymentGatewayResult.awaitingConfirmation("IN_PROGRESS"));
        payAt(now, "payment-unknown", idempotencyKey);

        // when: 주문은 아직 PAYMENT_PENDING이지만 마감 시각에 재전송한다
        PaymentResponse replay = payAt(order.getPaymentExpiresAt(), "payment-unknown", idempotencyKey);

        // then
        verify(paymentGateway, times(1)).confirm(any());
        assertThat(replay.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문과 마감이 지난 주문은 상태에 맞는 오류로 PG 호출 없이 거부한다")
    void rejectsUnpayableOrders() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime afterDeadline = order.getPaymentExpiresAt().plusSeconds(1);

        // when & then: 결제 대기인데 마감이 지남
        assertPayRejected(afterDeadline, PaymentErrorCode.PAYMENT_EXPIRED);
        // when & then: 만료 처리된 주문
        jdbcTemplate.update("UPDATE orders SET status = 'EXPIRED', expired_at = CURRENT_TIMESTAMP WHERE id = ?",
                order.getId());
        assertPayRejected(now, PaymentErrorCode.PAYMENT_EXPIRED);
        // when & then: 취소된 주문은 마감 전후와 관계없이 상태 전이 오류
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELED', canceled_at = CURRENT_TIMESTAMP WHERE id = ?",
                order.getId());
        assertPayRejected(now, CommonErrorCode.INVALID_STATE_TRANSITION);
        assertPayRejected(afterDeadline, CommonErrorCode.INVALID_STATE_TRANSITION);
        verify(paymentGateway, never()).confirm(any());
        assertThat(fixture.payments(order)).isEmpty();
    }

    private void assertPayRejected(OffsetDateTime requestTime, Object errorCode) {
        assertThatThrownBy(() -> payAt(requestTime, "payment-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private void assertUnchangedOrder() {
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.reservationStatus(order)).isEqualTo("HELD");
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY)
                .containsEntry("sold_quantity", 0);
    }
}
