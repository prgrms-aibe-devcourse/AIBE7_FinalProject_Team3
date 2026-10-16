package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentCancellation;
import org.example.grab.domain.payment.entity.PaymentCancellationPurpose;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.repository.PaymentCancellationRepository;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CompensationRefundTransactionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Long PAYMENT_ID = 10L;
    private static final Long CANCELLATION_ID = 1L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCancellationRepository cancellationRepository;

    @Mock
    private OrderPaymentService orderPaymentService;

    private CompensationRefundTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new CompensationRefundTransactionService(
                paymentRepository, cancellationRepository, orderPaymentService);
    }

    @Test
    @DisplayName("잠근 대상 결제마다 결제 금액 전액의 보정 환불 기록을 바로 환불 요청할 수 있게 만든다")
    void createsRefunds() {
        // given
        Payment payment = compensationPayment();
        given(paymentRepository.lockCompensationTargets(50)).willReturn(List.of(PAYMENT_ID));
        given(paymentRepository.findAllById(List.of(PAYMENT_ID))).willReturn(List.of(payment));

        // when
        int created = transactionService.createRefunds(NOW, 50);

        // then
        assertThat(created).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaymentCancellation>> captor = ArgumentCaptor.forClass(List.class);
        then(cancellationRepository).should().saveAll(captor.capture());
        PaymentCancellation cancellation = captor.getValue().get(0);
        assertThat(cancellation.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(cancellation.getPurpose()).isEqualTo(PaymentCancellationPurpose.LATE_APPROVAL_COMPENSATION);
        assertThat(cancellation.getRequestedBy()).isNull();
        assertThat(cancellation.getAmount()).isEqualTo(33000);
        assertThat(cancellation.getStatus()).isEqualTo(PaymentCancellationStatus.REQUESTED);
        assertThat(cancellation.getNextRetryAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("잠근 대상 결제가 없으면 기록을 만들지 않는다")
    void createsNothingWithoutTargets() {
        // given
        given(paymentRepository.lockCompensationTargets(anyInt())).willReturn(List.of());

        // when
        int created = transactionService.createRefunds(NOW, 50);

        // then
        assertThat(created).isZero();
        then(cancellationRepository).should(never()).saveAll(anyList());
    }

    @Test
    @DisplayName("환불 대상 값에는 서버 주문번호와 기록의 서버 멱등 키를 쓰는 취소 요청을 담는다")
    void findsRefundTarget() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        given(cancellationRepository.findById(CANCELLATION_ID)).willReturn(Optional.of(cancellation));
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(orderPaymentService.findForPaymentReconciliation(1L)).willReturn(new PayableOrder(
                1L, UUID.randomUUID(), "ORD-1", OrderStatus.EXPIRED, 33000, NOW.minusMinutes(5)));

        // when
        Optional<CompensationRefundTransactionService.RefundTarget> target =
                transactionService.findRefundTarget(CANCELLATION_ID);

        // then
        assertThat(target).isPresent();
        assertThat(target.get().orderNumber()).isEqualTo("ORD-1");
        assertThat(target.get().command().paymentKey()).isEqualTo("payment-key");
        assertThat(target.get().command().idempotencyKey()).isEqualTo(cancellation.getIdempotencyKey());
    }

    @Test
    @DisplayName("이미 끝난 기록이면 환불 대상 값이 비어 있다")
    void findsNothingForSettledCancellation() {
        // given
        PaymentCancellation cancellation = cancellation();
        cancellation.fail("NOT_CANCELABLE_PAYMENT", NOW);
        given(cancellationRepository.findById(CANCELLATION_ID)).willReturn(Optional.of(cancellation));

        // when & then
        assertThat(transactionService.findRefundTarget(CANCELLATION_ID)).isEmpty();
    }

    @Test
    @DisplayName("취소 성공이면 기록을 SUCCEEDED로, 결제를 CANCELED·보정 RESOLVED로 바꾼다")
    void appliesCanceled() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        givenLocked(payment, cancellation);
        OffsetDateTime canceledAt = NOW.minusSeconds(1);

        // when
        PaymentCancellationStatus status = transactionService.applyResult(
                CANCELLATION_ID, PaymentCancelResult.canceled("CANCELED", "tx-key", canceledAt), NOW);

        // then
        assertThat(status).isEqualTo(PaymentCancellationStatus.SUCCEEDED);
        assertThat(cancellation.getProviderCancelId()).isEqualTo("tx-key");
        assertThat(cancellation.getCompletedAt()).isEqualTo(NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    @Test
    @DisplayName("취소 거절이면 기록을 FAILED로 끝내고 결제는 보정 REQUIRED로 남긴다")
    void appliesRejected() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        givenLocked(payment, cancellation);

        // when
        PaymentCancellationStatus status = transactionService.applyResult(CANCELLATION_ID,
                PaymentCancelResult.rejected("NOT_CANCELABLE_PAYMENT", "취소할 수 없습니다."), NOW);

        // then
        assertThat(status).isEqualTo(PaymentCancellationStatus.FAILED);
        assertThat(cancellation.getFailureCode()).isEqualTo("NOT_CANCELABLE_PAYMENT");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.REQUIRED);
    }

    @Test
    @DisplayName("결과 불명이면 기록을 UNKNOWN으로 두고 1분 뒤 다시 요청하게 한다")
    void appliesUnknown() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        givenLocked(payment, cancellation);

        // when
        PaymentCancellationStatus status = transactionService.applyResult(
                CANCELLATION_ID, PaymentCancelResult.unknown(null, null, "응답 없음"), NOW);

        // then
        assertThat(status).isEqualTo(PaymentCancellationStatus.UNKNOWN);
        assertThat(cancellation.getNextRetryAt()).isEqualTo(NOW.plus(PaymentCancellation.RETRY_AFTER));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("다른 요청이 이미 결과를 확정한 기록은 바꾸지 않는다")
    void keepsSettledResult() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        cancellation.fail("NOT_CANCELABLE_PAYMENT", NOW);
        givenLocked(payment, cancellation);

        // when
        PaymentCancellationStatus status = transactionService.applyResult(
                CANCELLATION_ID, PaymentCancelResult.canceled("CANCELED", "tx-key", NOW), NOW);

        // then
        assertThat(status).isEqualTo(PaymentCancellationStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("주문번호 불일치는 PG에 요청하지 않았으므로 시도 횟수를 늘리지 않고 FAILED로 끝내고 결제는 보정 REQUIRED로 남긴다")
    void abandonsOrderMismatch() {
        // given
        Payment payment = compensationPayment();
        PaymentCancellation cancellation = cancellation();
        givenLocked(payment, cancellation);

        // when
        PaymentCancellationStatus status = transactionService.abandonOrderMismatch(CANCELLATION_ID, NOW);

        // then
        assertThat(status).isEqualTo(PaymentCancellationStatus.FAILED);
        assertThat(cancellation.getFailureCode()).isEqualTo(CompensationRefundTransactionService.ORDER_MISMATCH_CODE);
        assertThat(cancellation.getAttemptCount()).isZero();
        assertThat(cancellation.getNextRetryAt()).isNull();
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.REQUIRED);
    }

    private void givenLocked(Payment payment, PaymentCancellation cancellation) {
        given(cancellationRepository.findPaymentIdById(CANCELLATION_ID)).willReturn(Optional.of(PAYMENT_ID));
        given(paymentRepository.findByIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(cancellationRepository.findByIdForUpdate(CANCELLATION_ID)).willReturn(Optional.of(cancellation));
    }

    private static Payment compensationPayment() {
        Payment payment = Payment.request(1L, PaymentProvider.TOSS, "client-key", "hash", "payment-key", 33000);
        payment.succeedRequiringReconciliation(NOW.minusMinutes(1), "결제 마감 후 승인");
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private static PaymentCancellation cancellation() {
        return PaymentCancellation.requestCompensation(
                PAYMENT_ID, 33000, CompensationRefundTransactionService.REFUND_REASON, NOW);
    }
}
