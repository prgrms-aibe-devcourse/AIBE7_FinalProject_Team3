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
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.idempotency.RequestHashGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.Mockito.verify;

// PG는 승인했지만 주문을 확정할 수 없는 경우와, 결과가 확정되지 않은 이전 결제를 정리하는 경우를 확인한다.
@SpringBootTest
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
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PaymentPreparation.Ready wrongAmount = prepare("payment-amount", now);
        PaymentGatewayResult amountMismatch = PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT - 1000, now);

        // when: 금액이 다른 승인 응답을 반영한 뒤, 주문번호가 다른 승인 응답으로 한 번 더 시도한다
        transactionService.applyResult(wrongAmount.paymentId(), amountMismatch, null, now);
        PaymentPreparation.Ready wrongOrder = prepare("payment-order", now);
        PaymentGatewayResult orderMismatch = PaymentGatewayResult.approved(
                "DONE", "ORD-OTHER-000001", PaymentTestFixture.TOTAL_AMOUNT, now);
        transactionService.applyResult(wrongOrder.paymentId(), orderMismatch, null, now);

        // then
        assertThat(fixture.payments(order)).hasSize(2).allSatisfy(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertUnchangedOrder();
        List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reconciliation_reason FROM payments WHERE order_id = ?", String.class, order.getId());
        assertThat(reasons).allMatch(reason -> reason.contains("불일치"));
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
        return (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(UUID.randomUUID().toString()),
                RequestHash.from("d".repeat(64)), new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT), now);
    }

    // 결제 요청 시각을 고정한 PaymentService로 요청한다.
    private PaymentResponse payAt(OffsetDateTime requestTime, String paymentKey) {
        Clock clock = Clock.fixed(requestTime.toInstant(), ZoneOffset.UTC);
        PaymentService service = new PaymentService(paymentGateway, transactionService, requestHashGenerator, clock);
        return service.pay(fixture.buyerId(), order.getUuid(), UUID.randomUUID().toString(),
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT));
    }

    private void assertUnchangedOrder() {
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.reservationStatus(order)).isEqualTo("HELD");
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY)
                .containsEntry("sold_quantity", 0);
    }
}
