package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 보정 환불 배치(GR-88, ERD.md 3.3)를 실제 DB로 확인한다. 서비스 트랜잭션이 실제로 커밋된다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompensationRefundIntegrationTest {

    private static final OffsetDateTime PG_CANCELED_AT = OffsetDateTime.parse("2026-10-16T05:10:00Z");
    // PG가 서버 주문 금액과 다른 금액으로 승인해 보정 대상이 된다
    private static final long PG_APPROVED_AMOUNT = 99000;

    @Autowired
    private PaymentBatchService batchService;

    @Autowired
    private CompensationRefundTransactionService compensationTransactionService;

    @Autowired
    private PaymentTransactionService transactionService;

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
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // ===== 기록 생성과 선점 =====

    @Test
    @DisplayName("보정 환불 기록이 없는 보정 대기 승인에만 결제 금액 전액의 기록을 만들고, 이미 기록이 있으면 다시 만들지 않는다")
    void createsRefundOnlyForTargetsWithoutRecord() {
        // given
        Approved target = compensationPayment();
        Approved withRecord = compensationPayment();
        insertCancellation(withRecord.paymentId(), "LATE_APPROVAL_COMPENSATION", "FAILED", null);
        Approved paid = paidPayment();

        // when
        compensationTransactionService.createRefunds(now, 50);

        // then
        assertThat(compensationCount(target.paymentId())).isEqualTo(1);
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "REQUESTED")
                .containsEntry("requested_by", null)
                .containsEntry("amount", PaymentTestFixture.TOTAL_AMOUNT)
                .containsEntry("attempt_count", 0);
        assertThat(nextRetryAt(compensationId(target.paymentId()))).isAtSameInstantAs(now);
        assertThat(compensationCount(withRecord.paymentId())).isEqualTo(1);
        assertThat(compensationCount(paid.paymentId())).isZero();

        compensationTransactionService.createRefunds(now, 50);
        assertThat(compensationCount(target.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("결제당 보정 환불 기록은 부분 유일 인덱스로 하나만 저장된다")
    void uniqueIndexAllowsOneCompensationPerPayment() {
        // given
        Approved target = compensationPayment();
        insertCancellation(target.paymentId(), "LATE_APPROVAL_COMPENSATION", "FAILED", null);

        // when & then: 소비자 취소 기록은 제한하지 않는다
        assertThatThrownBy(() -> insertCancellation(
                target.paymentId(), "LATE_APPROVAL_COMPENSATION", "REQUESTED", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertCancellation(target.paymentId(), "ORDER_CANCEL", "FAILED", null);
        insertCancellation(target.paymentId(), "ORDER_CANCEL", "FAILED", null);
    }

    @Test
    @DisplayName("재요청 시각이 된 진행 중 보정 환불만 선점하고 next_retry_at을 1분 뒤로 바꾼다")
    void claimsOnlyDueCompensations() {
        // given: 결제당 보정 환불 기록은 하나뿐이라 결제를 따로 만든다
        Long dueRequested = insertCancellation(
                compensationPayment().paymentId(), "LATE_APPROVAL_COMPENSATION", "REQUESTED", now);
        Long dueUnknown = insertCancellation(
                compensationPayment().paymentId(), "LATE_APPROVAL_COMPENSATION", "UNKNOWN", now.minusSeconds(1));
        Long notDue = insertCancellation(
                compensationPayment().paymentId(), "LATE_APPROVAL_COMPENSATION", "UNKNOWN", now.plusSeconds(30));
        Long failed = insertCancellation(
                compensationPayment().paymentId(), "LATE_APPROVAL_COMPENSATION", "FAILED", null);
        Long orderCancel = insertCancellation(paidPayment().paymentId(), "ORDER_CANCEL", "UNKNOWN", now);

        // when
        List<Long> claimed = compensationTransactionService.claimRefunds(now, 50);

        // then
        assertThat(claimed).contains(dueRequested, dueUnknown).doesNotContain(notDue, failed, orderCancel);
        assertThat(nextRetryAt(dueRequested)).isAtSameInstantAs(now.plusMinutes(1));
        assertThat(compensationTransactionService.claimRefunds(now.plusSeconds(30), 50))
                .contains(notDue)
                .doesNotContain(dueRequested, dueUnknown);
    }

    // ===== 환불과 반영 =====

    @Test
    @DisplayName("한 주기에 기록을 만들고 환불까지 끝내 결제를 CANCELED·보정 RESOLVED로 바꾸며, 주문·재고는 그대로 두고 다시 결제할 수 있다")
    void refundsInOneCycleAndAllowsRepayment() {
        // given
        Approved target = compensationPayment();
        givenLookup(target, approvedLookup(target.order().getOrderNumber()));
        given(paymentGateway.cancel(any())).willReturn(canceled());

        // when
        int decided = batchService.refundCompensations(now);

        // then
        assertThat(decided).isGreaterThanOrEqualTo(1);
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("provider_cancel_id", "cancel-transaction-key")
                .containsEntry("attempt_count", 1)
                .containsEntry("next_retry_at", null);
        assertThat(paymentRow(target.paymentId()))
                .containsEntry("status", "CANCELED")
                .containsEntry("reconciliation_status", "RESOLVED");
        assertThat(jdbcTemplate.queryForObject("SELECT canceled_at FROM payments WHERE id = ?",
                OffsetDateTime.class, target.paymentId())).isAtSameInstantAs(PG_CANCELED_AT);
        assertThat(fixture.orderStatus(target.order())).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.reservationStatus(target.order())).isEqualTo("HELD");
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY)
                .containsEntry("sold_quantity", 0);
        assertThat(capturedCommands(1).get(0).idempotencyKey())
                .isEqualTo(compensationRow(target.paymentId()).get("idempotency_key"));

        // 보정 대기 결제가 없어져 마감 전 주문은 다시 결제할 수 있다
        assertThat(prepare(target.order())).isInstanceOf(PaymentPreparation.Ready.class);
    }

    @Test
    @DisplayName("PG 결제의 주문번호가 서버 주문과 다르면 취소를 요청하지 않고 FAILED(PG_ORDER_MISMATCH)로 끝내며 기록을 다시 만들지 않는다")
    void abandonsOrderMismatchWithoutCancel() {
        // given
        Approved target = compensationPayment();
        givenLookup(target, approvedLookup("ORD-OTHER"));

        // when
        batchService.refundCompensations(now);
        batchService.refundCompensations(now.plusMinutes(2));

        // then
        assertThat(compensationCount(target.paymentId())).isEqualTo(1);
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", CompensationRefundTransactionService.ORDER_MISMATCH_CODE)
                .containsEntry("attempt_count", 0)
                .containsEntry("next_retry_at", null);
        assertThat(paymentRow(target.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("reconciliation_status", "REQUIRED");
        verify(paymentGateway, never()).cancel(any());
        verify(paymentGateway, times(1)).lookup(target.paymentKey());
    }

    @Test
    @DisplayName("PG가 환불을 거절하면 FAILED로 끝내고 결제는 보정 REQUIRED로 남기며, 같은 거절을 반복하지 않는다")
    void keepsRequiredWhenRejected() {
        // given
        Approved target = compensationPayment();
        givenLookup(target, approvedLookup(target.order().getOrderNumber()));
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));

        // when
        batchService.refundCompensations(now);
        batchService.refundCompensations(now.plusMinutes(2));

        // then
        assertThat(compensationCount(target.paymentId())).isEqualTo(1);
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", "NOT_CANCELABLE_PAYMENT")
                .containsEntry("attempt_count", 1);
        assertThat(paymentRow(target.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("reconciliation_status", "REQUIRED");
        verify(paymentGateway, times(1)).cancel(any());
    }

    @Test
    @DisplayName("결과를 모르는 환불은 1분 뒤 다시 조회하고, PG에서 이미 취소됐으면 같은 서버 멱등 키로 요청해 성공으로 반영한다")
    void retriesUnknownWithSameKeyAndConfirmsCanceled() {
        // given: 첫 시도는 결과 불명, 다음 조회에서는 이미 취소된 상태다
        Approved target = compensationPayment();
        given(paymentGateway.lookup(target.paymentKey()))
                .willReturn(approvedLookup(target.order().getOrderNumber()))
                .willReturn(PaymentGatewayResult.notApproved("CANCELED", "CANCELED", "결제가 완료되지 않았습니다."));
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.unknown(null, null, "결제 취소 응답을 확인하지 못했습니다."))
                .willReturn(canceled());

        // when
        batchService.refundCompensations(now);
        Map<String, Object> unknown = compensationRow(target.paymentId());
        int notYet = batchService.refundCompensations(now.plusSeconds(30));
        batchService.refundCompensations(now.plusMinutes(2));

        // then
        assertThat(unknown).containsEntry("status", "UNKNOWN").containsEntry("attempt_count", 1);
        assertThat(notYet).isZero();
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 2);
        assertThat(paymentRow(target.paymentId()))
                .containsEntry("status", "CANCELED")
                .containsEntry("reconciliation_status", "RESOLVED");
        assertThat(capturedCommands(2)).extracting(PaymentCancelCommand::idempotencyKey)
                .containsOnly((String) unknown.get("idempotency_key"));
    }

    // ===== 동시성 =====

    @Test
    @DisplayName("여러 인스턴스가 동시에 기록을 만들어도 결제당 보정 환불 기록은 하나다")
    void concurrentCreationKeepsOneRecordPerPayment() throws Exception {
        // given
        List<Long> paymentIds = List.of(
                compensationPayment().paymentId(), compensationPayment().paymentId(), compensationPayment().paymentId());

        // when
        runConcurrently(List.of(
                () -> compensationTransactionService.createRefunds(now, 50),
                () -> compensationTransactionService.createRefunds(now, 50),
                () -> compensationTransactionService.createRefunds(now, 50)));

        // then
        for (Long paymentId : paymentIds) {
            assertThat(compensationCount(paymentId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 기록에 취소 성공이 동시에 반영돼도 한 번만 반영된다")
    void concurrentApplyIsOnce() throws Exception {
        // given
        Approved target = compensationPayment();
        compensationTransactionService.createRefunds(now, 50);
        Long cancellationId = compensationId(target.paymentId());

        // when
        List<PaymentCancellationStatus> results = runConcurrently(List.of(
                () -> compensationTransactionService.applyResult(cancellationId, canceled(), now),
                () -> compensationTransactionService.applyResult(cancellationId, canceled(), now)));

        // then
        assertThat(results).containsOnly(PaymentCancellationStatus.SUCCEEDED);
        assertThat(compensationRow(target.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 1);
        assertThat(paymentRow(target.paymentId()))
                .containsEntry("status", "CANCELED")
                .containsEntry("reconciliation_status", "RESOLVED");
    }

    // PG가 서버 주문 금액과 다른 금액으로 승인해, 주문은 결제 대기로 남고 결제는 SUCCEEDED·보정 REQUIRED인 상태를 만든다.
    private Approved compensationPayment() {
        return approve(PG_APPROVED_AMOUNT);
    }

    // 주문을 확정한 승인(PAID). 보정 환불 대상이 아니다.
    private Approved paidPayment() {
        return approve(PaymentTestFixture.TOTAL_AMOUNT);
    }

    private Approved approve(long pgAmount) {
        Order order = fixture.createOrder();
        String paymentKey = "payment-" + UUID.randomUUID();
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(newKey()), RequestHash.from("c".repeat(64)),
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT), now);
        transactionService.applyResult(ready.paymentId(),
                PaymentGatewayResult.approved("DONE", order.getOrderNumber(), pgAmount, now), null, now);
        return new Approved(order, ready.paymentId(), paymentKey);
    }

    private PaymentPreparation prepare(Order order) {
        return transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(newKey()), RequestHash.from("d".repeat(64)),
                new PaymentRequest("payment-" + UUID.randomUUID(), PaymentTestFixture.TOTAL_AMOUNT), now);
    }

    private void givenLookup(Approved target, PaymentGatewayResult result) {
        given(paymentGateway.lookup(target.paymentKey())).willReturn(result);
    }

    private PaymentGatewayResult approvedLookup(String orderNumber) {
        return PaymentGatewayResult.approved("DONE", orderNumber, PG_APPROVED_AMOUNT, now);
    }

    private static PaymentCancelResult canceled() {
        return PaymentCancelResult.canceled("CANCELED", "cancel-transaction-key", PG_CANCELED_AT);
    }

    // 선점 조건만 확인하는 테스트용 취소 기록
    private Long insertCancellation(Long paymentId, String purpose, String status, OffsetDateTime nextRetryAt) {
        boolean completed = status.equals("SUCCEEDED") || status.equals("FAILED");
        return jdbcTemplate.queryForObject("""
                INSERT INTO payment_cancellations
                    (payment_id, purpose, idempotency_key, amount, reason, status, next_retry_at, completed_at)
                VALUES (?, ?, ?, ?, '환불', ?, ?, ?)
                RETURNING id
                """, Long.class, paymentId, purpose, UUID.randomUUID().toString(), PaymentTestFixture.TOTAL_AMOUNT,
                status, nextRetryAt, completed ? now : null);
    }

    private <T> List<T> runConcurrently(List<Callable<? extends T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<? extends T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<PaymentCancelCommand> capturedCommands(int count) {
        ArgumentCaptor<PaymentCancelCommand> captor = ArgumentCaptor.forClass(PaymentCancelCommand.class);
        verify(paymentGateway, times(count)).cancel(captor.capture());
        return captor.getAllValues();
    }

    private Map<String, Object> compensationRow(Long paymentId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, requested_by, amount, attempt_count, idempotency_key, provider_cancel_id,
                       failure_code, next_retry_at
                FROM payment_cancellations
                WHERE payment_id = ? AND purpose = 'LATE_APPROVAL_COMPENSATION'
                """, paymentId);
    }

    private Long compensationId(Long paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM payment_cancellations
                WHERE payment_id = ? AND purpose = 'LATE_APPROVAL_COMPENSATION'
                """, Long.class, paymentId);
    }

    private int compensationCount(Long paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment_cancellations
                WHERE payment_id = ? AND purpose = 'LATE_APPROVAL_COMPENSATION'
                """, Integer.class, paymentId);
    }

    private OffsetDateTime nextRetryAt(Long cancellationId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM payment_cancellations WHERE id = ?", OffsetDateTime.class, cancellationId);
    }

    private Map<String, Object> paymentRow(Long paymentId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reconciliation_status FROM payments WHERE id = ?", paymentId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private record Approved(Order order, Long paymentId, String paymentKey) {
    }
}
