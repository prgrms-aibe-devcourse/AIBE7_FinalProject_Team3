package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentInProgressCleanupServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 13, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private PaymentTransactionService transactionService;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentInProgressCleanupService cleanupService;

    @Test
    @DisplayName("주기당 상한만큼 같은 now로 선점하고, 진행 중이 아닌 상태로 확정한 결제 수를 돌려준다")
    void claimsAndCountsSettled() {
        // given: 1번은 승인으로 확정되고, 2번은 조회로도 결과를 몰라 UNKNOWN으로 남는다
        given(transactionService.claimInProgress(NOW, PaymentInProgressCleanupService.BATCH_SIZE))
                .willReturn(List.of(1L, 2L));
        ReconciliationTarget first = target(1L, PaymentStatus.PENDING, NOW.minusMinutes(1));
        ReconciliationTarget second = target(2L, PaymentStatus.UNKNOWN, NOW.minusMinutes(2));
        given(transactionService.findReconciliationTarget(1L, NOW)).willReturn(Optional.of(first));
        given(transactionService.findReconciliationTarget(2L, NOW)).willReturn(Optional.of(second));
        given(paymentService.reconcile(first)).willReturn(payment(PaymentStatus.FAILED));
        given(paymentService.reconcile(second)).willReturn(payment(PaymentStatus.UNKNOWN));

        // when
        int settled = cleanupService.cleanUp(NOW);

        // then
        assertThat(settled).isEqualTo(1);
    }

    @Test
    @DisplayName("한 결제의 정리가 실패해도 나머지 결제를 계속 정리한다")
    void isolatesFailurePerPayment() {
        // given
        given(transactionService.claimInProgress(NOW, PaymentInProgressCleanupService.BATCH_SIZE))
                .willReturn(List.of(1L, 2L));
        ReconciliationTarget second = target(2L, PaymentStatus.UNKNOWN, NOW.minusMinutes(2));
        given(transactionService.findReconciliationTarget(1L, NOW)).willThrow(new RuntimeException("일시적 DB 오류"));
        given(transactionService.findReconciliationTarget(2L, NOW)).willReturn(Optional.of(second));
        given(paymentService.reconcile(second)).willReturn(payment(PaymentStatus.FAILED));

        // when
        int settled = cleanupService.cleanUp(NOW);

        // then
        assertThat(settled).isEqualTo(1);
    }

    @Test
    @DisplayName("선점한 뒤 다른 요청이 먼저 결과를 확정했으면 PG를 호출하지 않는다")
    void skipsAlreadySettledPayment() {
        // given
        given(transactionService.claimInProgress(NOW, PaymentInProgressCleanupService.BATCH_SIZE))
                .willReturn(List.of(1L));
        given(transactionService.findReconciliationTarget(1L, NOW)).willReturn(Optional.empty());

        // when
        int settled = cleanupService.cleanUp(NOW);

        // then
        assertThat(settled).isEqualTo(1);
        then(paymentService).should(never()).reconcile(any());
    }

    @Test
    @DisplayName("선점한 결제가 없으면 정리를 호출하지 않는다")
    void doesNothingWithoutClaims() {
        // given
        given(transactionService.claimInProgress(NOW, PaymentInProgressCleanupService.BATCH_SIZE))
                .willReturn(List.of());

        // when
        int settled = cleanupService.cleanUp(NOW);

        // then
        assertThat(settled).isZero();
        then(transactionService).should(never()).findReconciliationTarget(any(), any());
    }

    private static ReconciliationTarget target(Long paymentId, PaymentStatus status, OffsetDateTime createdAt) {
        return new ReconciliationTarget(paymentId,
                new PaymentConfirmCommand("payment-" + paymentId, "ORD-1", 33000, "server-key-" + paymentId),
                true, status, createdAt);
    }

    private static Payment payment(PaymentStatus status) {
        Payment payment = Payment.request(1L, PaymentProvider.TOSS, "client-key", "hash", "payment-key", 33000);
        if (status == PaymentStatus.FAILED) {
            payment.fail("REJECT_CARD_PAYMENT", "거절");
        } else if (status == PaymentStatus.UNKNOWN) {
            payment.markUnknown("PG 승인 결과 확인 불가");
        }
        return payment;
    }
}
