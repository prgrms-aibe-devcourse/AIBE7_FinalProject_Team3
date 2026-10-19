package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentCancellation;
import org.example.grab.domain.payment.entity.PaymentCancellationPurpose;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.repository.PaymentCancellationRepository;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
    보정 환불 배치(GR-88, ERD.md 3.3)의 DB 트랜잭션을 맡는다. PG 조회·취소는 트랜잭션 밖에서 CompensationRefundService가 한다.
    - createRefunds: 보정 환불 기록이 없는 대상 결제를 잠그고 기록(REQUESTED)을 만든다. PG는 호출하지 않는다.
    - claimRefunds·findRefundTarget: 진행 중인 기록을 선점하고, 환불을 요청하는 데 필요한 값을 읽는다.
    - applyResult·abandonOrderMismatch: 결제 → 기록 순서로 행을 잠그고 결과를 반영한다.
    주문·예약·재고는 잠그지도 바꾸지도 않는다. 대상 결제는 주문을 확정한 적이 없다.
 */
@Service
@RequiredArgsConstructor
public class CompensationRefundTransactionService {

    // PG 취소 사유로 보낸다. 내부 보정 사유(reconciliation_reason)는 PG로 흘려보내지 않는다.
    static final String REFUND_REASON = "주문을 확정하지 못한 결제의 환불";
    // PG에 조회한 결제의 주문번호가 서버 주문번호와 다르다. 다른 주문의 결제일 수 있어 환불하지 않는다.
    static final String ORDER_MISMATCH_CODE = "PG_ORDER_MISMATCH";

    private final PaymentRepository paymentRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final OrderPaymentService orderPaymentService;

    /**
     * 보정 환불 기록이 없는 대상 결제를 최대 limit건 잠그고 결제마다 기록을 만든다. 만든 기록 수를 돌려준다.
     * 결제당 기록은 uq_payment_cancellations_compensation으로 하나만 저장된다.
     */
    @Transactional
    public int createRefunds(OffsetDateTime now, int limit) {
        List<Long> paymentIds = paymentRepository.lockCompensationTargets(limit);
        if (paymentIds.isEmpty()) {
            return 0;
        }
        List<PaymentCancellation> cancellations = paymentRepository.findAllById(paymentIds).stream()
                .map(payment -> PaymentCancellation.requestCompensation(
                        payment.getId(), payment.getAmount(), REFUND_REASON, now))
                .toList();
        cancellationRepository.saveAll(cancellations);
        return cancellations.size();
    }

    /**
     * 진행 중인 보정 환불 기록을 최대 limit건 선점하고 ID를 돌려준다. 선점한 기록은 RETRY_AFTER 동안 다시 선점되지 않는다.
     */
    @Transactional
    public List<Long> claimRefunds(OffsetDateTime now, int limit) {
        return cancellationRepository.claimCompensationRefunds(now, now.plus(PaymentCancellation.RETRY_AFTER), limit);
    }

    /**
     * 선점한 기록의 환불을 요청하는 데 필요한 값을 읽는다. 잠그지 않는다. 그사이 다른 인스턴스가 결과를 확정했으면 비어 있다.
     */
    @Transactional(readOnly = true)
    public Optional<RefundTarget> findRefundTarget(Long cancellationId) {
        PaymentCancellation cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("결제 취소 요청이 없습니다: " + cancellationId));
        if (cancellation.getPurpose() != PaymentCancellationPurpose.LATE_APPROVAL_COMPENSATION) {
            throw new IllegalStateException("보정 환불 기록이 아닙니다: " + cancellationId);
        }
        if (!cancellation.isInProgress()) {
            return Optional.empty();
        }
        Payment payment = paymentRepository.findById(cancellation.getPaymentId()).orElseThrow();
        String orderNumber = orderPaymentService.findForPaymentReconciliation(payment.getOrderId()).orderNumber();
        return Optional.of(new RefundTarget(cancellation.getId(), cancellation.getCreatedAt(), orderNumber,
                new PaymentCancelCommand(payment.getProviderPaymentId(), cancellation.getReason(),
                        cancellation.getIdempotencyKey())));
    }

    /**
     * PG 결제 취소 결과를 반영하고 기록의 상태를 돌려준다. 다른 요청이 이미 결과를 확정했으면 바꾸지 않는다.
     * 거절되면 결제는 보정 REQUIRED로 남는다. 기록을 다시 만들지 않으므로 운영자가 확인한다.
     */
    @Transactional
    public PaymentCancellationStatus applyResult(Long cancellationId, PaymentCancelResult result, OffsetDateTime now) {
        Locked locked = lock(cancellationId);
        PaymentCancellation cancellation = locked.cancellation();
        if (!cancellation.isInProgress()) {
            return cancellation.getStatus();
        }
        switch (result.outcome()) {
            case CANCELED -> {
                cancellation.succeed(result.transactionKey(), now);
                locked.payment().cancelForCompensation(result.canceledAt());
            }
            case REJECTED -> cancellation.fail(result.failureCode(), now);
            case UNKNOWN -> cancellation.markUnknown(result.failureCode(), now);
        }
        return cancellation.getStatus();
    }

    /**
     * PG에 조회한 결제의 주문번호가 서버 주문과 달라 환불하지 않고 기록을 끝낸다. 결제는 보정 REQUIRED로 남는다.
     * 다른 요청이 이미 결과를 확정했으면 바꾸지 않는다. 반영 후 기록의 상태를 돌려준다.
     */
    @Transactional
    public PaymentCancellationStatus abandonOrderMismatch(Long cancellationId, OffsetDateTime now) {
        PaymentCancellation cancellation = lock(cancellationId).cancellation();
        if (cancellation.isInProgress()) {
            cancellation.abandon(ORDER_MISMATCH_CODE, now);
        }
        return cancellation.getStatus();
    }

    // 결제 → 기록 순서로 잠근다. 잠그기 전에 결제 ID만 읽어, 영속성 컨텍스트에 옛 상태가 남지 않게 한다.
    private Locked lock(Long cancellationId) {
        Long paymentId = cancellationRepository.findPaymentIdById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("결제 취소 요청이 없습니다: " + cancellationId));
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
        PaymentCancellation cancellation = cancellationRepository.findByIdForUpdate(cancellationId).orElseThrow();
        return new Locked(payment, cancellation);
    }

    private record Locked(Payment payment, PaymentCancellation cancellation) {
    }

    /**
     * 보정 환불 배치가 환불을 요청할 기록.
     *
     * @param createdAt   오래 진행 중인 기록의 경고에 쓴다
     * @param orderNumber PG에 조회한 결제의 주문번호와 비교할 서버 주문번호
     * @param command     기록의 서버 멱등 키를 쓰는 전액 취소 요청
     */
    public record RefundTarget(
            Long cancellationId, OffsetDateTime createdAt, String orderNumber, PaymentCancelCommand command) {
    }
}
