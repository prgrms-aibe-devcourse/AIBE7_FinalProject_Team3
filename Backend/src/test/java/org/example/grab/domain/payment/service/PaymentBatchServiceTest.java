package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentBatchServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 13, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private PaymentTransactionService transactionService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private OrderPaymentService orderPaymentService;

    @Mock
    private OrderCancelTransactionService cancelTransactionService;

    @Mock
    private OrderCancelService orderCancelService;

    @Mock
    private CompensationRefundTransactionService compensationTransactionService;

    @Mock
    private CompensationRefundService compensationRefundService;

    private StepClock clock;
    private PaymentBatchService batchService;

    @BeforeEach
    void setUp() {
        clock = new StepClock(NOW.toInstant());
        batchService = new PaymentBatchService(transactionService, paymentService, orderPaymentService,
                cancelTransactionService, orderCancelService, compensationTransactionService,
                compensationRefundService, clock);
    }

    @Nested
    @DisplayName("한 주기")
    class Run {

        @Test
        @DisplayName("진행 중 결제 정리 → 만료 → 취소 재요청 → 보정 환불 순서로 같은 now로 실행한다")
        void runsStepsInOrder() {
            // when
            batchService.run(NOW);

            // then
            InOrder order = inOrder(transactionService, orderPaymentService, cancelTransactionService,
                    compensationTransactionService);
            order.verify(transactionService).claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE);
            order.verify(orderPaymentService)
                    .findPaymentExpiryCandidates(NOW, null, PaymentBatchService.EXPIRY_BATCH_SIZE);
            order.verify(cancelTransactionService).claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE);
            order.verify(compensationTransactionService)
                    .createRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE);
            order.verify(compensationTransactionService)
                    .claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE);
        }

        @Test
        @DisplayName("앞 단계에서 예외가 나도 다음 단계를 실행하고, 예외를 밖으로 전파하지 않는다")
        void runsNextStepsEvenIfPreviousFails() {
            // given
            given(transactionService.claimInProgress(any(), anyInt()))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            given(orderPaymentService.findPaymentExpiryCandidates(any(), any(), anyInt()))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            given(cancelTransactionService.claimRetries(any(), anyInt()))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            given(compensationTransactionService.claimRefunds(any(), anyInt()))
                    .willThrow(new RuntimeException("일시적 DB 오류"));

            // when & then
            assertThatCode(() -> batchService.run(NOW)).doesNotThrowAnyException();
            then(orderPaymentService).should()
                    .findPaymentExpiryCandidates(NOW, null, PaymentBatchService.EXPIRY_BATCH_SIZE);
            then(cancelTransactionService).should().claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE);
            then(compensationTransactionService).should()
                    .claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE);
        }
    }

    @Nested
    @DisplayName("진행 중 결제 정리")
    class CleanUpInProgress {

        @Test
        @DisplayName("주기당 상한만큼 선점하고, 진행 중이 아닌 상태로 확정한 결제 수를 돌려준다")
        void claimsAndCountsSettled() {
            // given: 1번은 실패로 확정되고, 2번은 조회로도 결과를 몰라 UNKNOWN으로 남는다
            given(transactionService.claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L));
            PaymentPreparation.Resolve first = target(1L);
            PaymentPreparation.Resolve second = target(2L);
            given(transactionService.findReconciliationTarget(1L)).willReturn(Optional.of(first));
            given(transactionService.findReconciliationTarget(2L)).willReturn(Optional.of(second));
            given(paymentService.reconcile(first)).willReturn(payment(PaymentStatus.FAILED));
            given(paymentService.reconcile(second)).willReturn(payment(PaymentStatus.UNKNOWN));

            // when
            int settled = batchService.cleanUpInProgress(NOW);

            // then
            assertThat(settled).isEqualTo(1);
        }

        @Test
        @DisplayName("한 결제의 정리가 실패해도 나머지 결제를 계속 정리한다")
        void isolatesFailurePerPayment() {
            // given
            given(transactionService.claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L));
            PaymentPreparation.Resolve second = target(2L);
            given(transactionService.findReconciliationTarget(1L)).willThrow(new RuntimeException("일시적 DB 오류"));
            given(transactionService.findReconciliationTarget(2L)).willReturn(Optional.of(second));
            given(paymentService.reconcile(second)).willReturn(payment(PaymentStatus.FAILED));

            // when
            int settled = batchService.cleanUpInProgress(NOW);

            // then
            assertThat(settled).isEqualTo(1);
        }

        @Test
        @DisplayName("시간 예산을 넘기면 남은 선점 결제는 처리하지 않고 넘긴다")
        void defersRemainingPaymentsAfterTimeBudget() {
            // given: 첫 결제의 PG 처리가 예산보다 오래 걸린다
            given(transactionService.claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L, 3L));
            PaymentPreparation.Resolve first = target(1L);
            given(transactionService.findReconciliationTarget(1L)).willReturn(Optional.of(first));
            given(paymentService.reconcile(first)).willAnswer(invocation -> {
                clock.advance(PaymentBatchService.CLEANUP_TIME_BUDGET.plusSeconds(1));
                return payment(PaymentStatus.UNKNOWN);
            });

            // when
            batchService.cleanUpInProgress(NOW);

            // then
            then(transactionService).should(never()).findReconciliationTarget(2L);
            then(transactionService).should(never()).findReconciliationTarget(3L);
        }

        @Test
        @DisplayName("선점한 뒤 다른 요청이 먼저 결과를 확정했으면 PG를 호출하지 않는다")
        void skipsAlreadySettledPayment() {
            // given
            given(transactionService.claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE))
                    .willReturn(List.of(1L));
            given(transactionService.findReconciliationTarget(1L)).willReturn(Optional.empty());

            // when
            int settled = batchService.cleanUpInProgress(NOW);

            // then
            assertThat(settled).isEqualTo(1);
            then(paymentService).should(never()).reconcile(any());
        }

        @Test
        @DisplayName("선점한 결제가 없으면 정리를 호출하지 않는다")
        void doesNothingWithoutClaims() {
            // given
            given(transactionService.claimInProgress(NOW, PaymentBatchService.CLEANUP_BATCH_SIZE))
                    .willReturn(List.of());

            // when
            int settled = batchService.cleanUpInProgress(NOW);

            // then
            assertThat(settled).isZero();
            then(transactionService).should(never()).findReconciliationTarget(any());
        }
    }

    @Nested
    @DisplayName("결제 대기 만료")
    class ExpireDue {

        @Test
        @DisplayName("페이지가 가득 차면 마지막 후보를 커서로 다음 페이지를 같은 now로 읽고, 덜 차면 멈춘다")
        void readsPagesWithSameNow() {
            // given: 첫 페이지는 EXPIRY_BATCH_SIZE건, 둘째 페이지는 1건
            int pageSize = PaymentBatchService.EXPIRY_BATCH_SIZE;
            List<PaymentExpiryCandidate> firstPage = candidates(1, pageSize);
            PaymentExpiryCandidate last = firstPage.get(firstPage.size() - 1);
            List<PaymentExpiryCandidate> secondPage = candidates(pageSize + 1, 1);
            given(orderPaymentService.findPaymentExpiryCandidates(eq(NOW), isNull(), eq(pageSize)))
                    .willReturn(firstPage);
            given(orderPaymentService.findPaymentExpiryCandidates(NOW, last, pageSize)).willReturn(secondPage);
            given(orderPaymentService.expireIfDueSkippingLocked(anyLong(), eq(NOW)))
                    .willReturn(PaymentExpiryResult.EXPIRED);

            // when
            int expired = batchService.expireDue(NOW);

            // then
            assertThat(expired).isEqualTo(pageSize + 1);
            then(orderPaymentService).should(never()).findPaymentExpiryCandidates(NOW, secondPage.get(0), pageSize);
        }

        @Test
        @DisplayName("한 주문의 만료가 실패해도 나머지 주문을 계속 만료하고, 잠겨 건너뛴 주문은 만료 수에 넣지 않는다")
        void isolatesFailurePerOrder() {
            // given
            given(orderPaymentService.findPaymentExpiryCandidates(
                    any(), isNull(), eq(PaymentBatchService.EXPIRY_BATCH_SIZE)))
                    .willReturn(candidates(1, 4));
            given(orderPaymentService.expireIfDueSkippingLocked(1L, NOW))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            given(orderPaymentService.expireIfDueSkippingLocked(2L, NOW)).willReturn(PaymentExpiryResult.SKIPPED_LOCKED);
            given(orderPaymentService.expireIfDueSkippingLocked(3L, NOW)).willReturn(PaymentExpiryResult.NOT_DUE);
            given(orderPaymentService.expireIfDueSkippingLocked(4L, NOW)).willReturn(PaymentExpiryResult.EXPIRED);

            // when
            int expired = batchService.expireDue(NOW);

            // then
            assertThat(expired).isEqualTo(1);
            then(orderPaymentService).should().expireIfDueSkippingLocked(4L, NOW);
        }

        @Test
        @DisplayName("후보가 없으면 만료를 호출하지 않는다")
        void doesNothingWithoutCandidates() {
            // given
            given(orderPaymentService.findPaymentExpiryCandidates(
                    any(), isNull(), eq(PaymentBatchService.EXPIRY_BATCH_SIZE)))
                    .willReturn(List.of());

            // when
            int expired = batchService.expireDue(NOW);

            // then
            assertThat(expired).isZero();
            then(orderPaymentService).should(never()).expireIfDueSkippingLocked(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("결제 취소 재요청")
    class RetryCancellations {

        @Test
        @DisplayName("주기당 상한만큼 선점하고, 성공·거절로 확정한 취소 수를 돌려준다")
        void claimsAndCountsDecided() {
            // given: 1번은 취소 성공, 2번은 거절, 3번은 여전히 결과를 모른다
            given(cancelTransactionService.claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L, 3L));
            givenRetry(1L, OrderCancelTransactionService.CancelOutcome.completed(response(RefundStatus.SUCCEEDED)));
            givenRetry(2L, OrderCancelTransactionService.CancelOutcome.rejection());
            givenRetry(3L, OrderCancelTransactionService.CancelOutcome.completed(response(RefundStatus.UNKNOWN)));

            // when
            int decided = batchService.retryCancellations(NOW);

            // then
            assertThat(decided).isEqualTo(2);
        }

        @Test
        @DisplayName("한 취소의 재요청이 실패해도 나머지 취소를 계속 재요청한다")
        void isolatesFailurePerCancellation() {
            // given
            given(cancelTransactionService.claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L));
            given(cancelTransactionService.findRetryTarget(1L)).willThrow(new RuntimeException("일시적 DB 오류"));
            givenRetry(2L, OrderCancelTransactionService.CancelOutcome.completed(response(RefundStatus.SUCCEEDED)));

            // when
            int decided = batchService.retryCancellations(NOW);

            // then
            assertThat(decided).isEqualTo(1);
        }

        @Test
        @DisplayName("시간 예산을 넘기면 남은 선점 취소는 처리하지 않고 넘긴다")
        void defersRemainingCancellationsAfterTimeBudget() {
            // given: 첫 취소의 PG 처리가 예산보다 오래 걸린다
            given(cancelTransactionService.claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L, 3L));
            OrderCancelTransactionService.CancelRetryTarget first = retryTarget(1L);
            given(cancelTransactionService.findRetryTarget(1L)).willReturn(Optional.of(first));
            given(orderCancelService.retry(first)).willAnswer(invocation -> {
                clock.advance(PaymentBatchService.CANCEL_RETRY_TIME_BUDGET.plusSeconds(1));
                return OrderCancelTransactionService.CancelOutcome.completed(response(RefundStatus.UNKNOWN));
            });

            // when
            batchService.retryCancellations(NOW);

            // then
            then(cancelTransactionService).should(never()).findRetryTarget(2L);
            then(cancelTransactionService).should(never()).findRetryTarget(3L);
        }

        @Test
        @DisplayName("선점한 뒤 다른 요청이 먼저 결과를 확정했으면 PG에 다시 요청하지 않는다")
        void skipsAlreadySettledCancellation() {
            // given
            given(cancelTransactionService.claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE))
                    .willReturn(List.of(1L));
            given(cancelTransactionService.findRetryTarget(1L)).willReturn(Optional.empty());

            // when
            int decided = batchService.retryCancellations(NOW);

            // then
            assertThat(decided).isZero();
            then(orderCancelService).should(never()).retry(any());
        }

        @Test
        @DisplayName("선점한 취소가 없으면 재요청을 호출하지 않는다")
        void doesNothingWithoutClaims() {
            // given
            given(cancelTransactionService.claimRetries(NOW, PaymentBatchService.CANCEL_RETRY_BATCH_SIZE))
                    .willReturn(List.of());

            // when
            int decided = batchService.retryCancellations(NOW);

            // then
            assertThat(decided).isZero();
            then(cancelTransactionService).should(never()).findRetryTarget(any());
        }

        private void givenRetry(Long cancellationId, OrderCancelTransactionService.CancelOutcome outcome) {
            OrderCancelTransactionService.CancelRetryTarget target = retryTarget(cancellationId);
            given(cancelTransactionService.findRetryTarget(cancellationId)).willReturn(Optional.of(target));
            given(orderCancelService.retry(target)).willReturn(outcome);
        }
    }

    @Nested
    @DisplayName("보정 환불")
    class RefundCompensations {

        @Test
        @DisplayName("기록을 만든 뒤 선점한 기록의 환불을 요청하고, 성공·거절·주문번호 불일치로 확정한 수를 돌려준다")
        void createsThenRefundsAndCountsDecided() {
            // given
            given(compensationTransactionService.claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L, 3L, 4L));
            givenRefund(1L, CompensationRefundService.RefundOutcome.SUCCEEDED);
            givenRefund(2L, CompensationRefundService.RefundOutcome.REJECTED);
            givenRefund(3L, CompensationRefundService.RefundOutcome.ORDER_MISMATCH);
            givenRefund(4L, CompensationRefundService.RefundOutcome.UNSETTLED);

            // when
            int decided = batchService.refundCompensations(NOW);

            // then
            assertThat(decided).isEqualTo(3);
            InOrder order = inOrder(compensationTransactionService);
            order.verify(compensationTransactionService)
                    .createRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE);
            order.verify(compensationTransactionService)
                    .claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE);
        }

        @Test
        @DisplayName("기록 생성이 실패해도 이미 있는 기록의 환불을 요청한다")
        void refundsEvenIfCreationFails() {
            // given
            given(compensationTransactionService.createRefunds(any(), anyInt()))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            given(compensationTransactionService.claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE))
                    .willReturn(List.of(1L));
            givenRefund(1L, CompensationRefundService.RefundOutcome.SUCCEEDED);

            // when
            int decided = batchService.refundCompensations(NOW);

            // then
            assertThat(decided).isEqualTo(1);
        }

        @Test
        @DisplayName("한 기록의 환불이 실패해도 나머지 기록을 계속 처리한다")
        void isolatesFailurePerCancellation() {
            // given
            given(compensationTransactionService.claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L));
            given(compensationTransactionService.findRefundTarget(1L))
                    .willThrow(new RuntimeException("일시적 DB 오류"));
            givenRefund(2L, CompensationRefundService.RefundOutcome.SUCCEEDED);

            // when
            int decided = batchService.refundCompensations(NOW);

            // then
            assertThat(decided).isEqualTo(1);
        }

        @Test
        @DisplayName("시간 예산을 넘기면 남은 선점 기록은 처리하지 않고 넘긴다")
        void defersRemainingAfterTimeBudget() {
            // given: 첫 기록의 PG 처리가 예산보다 오래 걸린다
            given(compensationTransactionService.claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE))
                    .willReturn(List.of(1L, 2L));
            CompensationRefundTransactionService.RefundTarget first = refundTarget(1L);
            given(compensationTransactionService.findRefundTarget(1L)).willReturn(Optional.of(first));
            given(compensationRefundService.refund(first)).willAnswer(invocation -> {
                clock.advance(PaymentBatchService.COMPENSATION_TIME_BUDGET.plusSeconds(1));
                return CompensationRefundService.RefundOutcome.UNSETTLED;
            });

            // when
            batchService.refundCompensations(NOW);

            // then
            then(compensationTransactionService).should(never()).findRefundTarget(2L);
        }

        @Test
        @DisplayName("선점한 뒤 다른 인스턴스가 먼저 결과를 확정했으면 PG를 호출하지 않는다")
        void skipsAlreadySettled() {
            // given
            given(compensationTransactionService.claimRefunds(NOW, PaymentBatchService.COMPENSATION_BATCH_SIZE))
                    .willReturn(List.of(1L));
            given(compensationTransactionService.findRefundTarget(1L)).willReturn(Optional.empty());

            // when
            int decided = batchService.refundCompensations(NOW);

            // then
            assertThat(decided).isZero();
            then(compensationRefundService).should(never()).refund(any());
        }

        private void givenRefund(Long cancellationId, CompensationRefundService.RefundOutcome outcome) {
            CompensationRefundTransactionService.RefundTarget target = refundTarget(cancellationId);
            given(compensationTransactionService.findRefundTarget(cancellationId)).willReturn(Optional.of(target));
            given(compensationRefundService.refund(target)).willReturn(outcome);
        }
    }

    private static CompensationRefundTransactionService.RefundTarget refundTarget(Long cancellationId) {
        return new CompensationRefundTransactionService.RefundTarget(cancellationId, NOW.minusMinutes(2), "ORD-1",
                new PaymentCancelCommand("payment-key", "환불", "server-key-" + cancellationId));
    }

    private static OrderCancelTransactionService.CancelRetryTarget retryTarget(Long cancellationId) {
        return new OrderCancelTransactionService.CancelRetryTarget(cancellationId, NOW.minusMinutes(2),
                new PaymentCancelCommand("payment-key", "단순 변심", "server-key-" + cancellationId));
    }

    private static OrderCancelResponse response(RefundStatus refundStatus) {
        OrderStatus status = refundStatus == RefundStatus.SUCCEEDED ? OrderStatus.CANCELED : OrderStatus.PAID;
        OffsetDateTime canceledAt = refundStatus == RefundStatus.SUCCEEDED ? NOW : null;
        return new OrderCancelResponse(UUID.randomUUID(), status, refundStatus, canceledAt);
    }

    private static PaymentPreparation.Resolve target(Long paymentId) {
        return new PaymentPreparation.Resolve(paymentId,
                new PaymentConfirmCommand("payment-" + paymentId, "ORD-1", 33000, "server-key-" + paymentId));
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

    private static List<PaymentExpiryCandidate> candidates(long firstId, int count) {
        return LongStream.range(firstId, firstId + count)
                .mapToObj(id -> (PaymentExpiryCandidate) new Candidate(id, NOW.toInstant().minusSeconds(60)))
                .toList();
    }

    private record Candidate(Long getId, Instant getPaymentExpiresAt) implements PaymentExpiryCandidate {
    }

    // 시간 예산 검증용 시계. advance로 PG 처리에 걸린 시간을 흉내 낸다.
    private static final class StepClock extends Clock {

        private Instant instant;

        private StepClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
