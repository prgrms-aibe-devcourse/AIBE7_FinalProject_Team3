package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.CancelableOrder;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentCancellation;
import org.example.grab.domain.payment.entity.PaymentCancellationPurpose;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.repository.PaymentCancellationRepository;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
    주문 취소의 DB 트랜잭션 두 개를 맡는다(ERD.md 3.3). PG 결제 취소는 두 트랜잭션 사이, 트랜잭션 밖에서 OrderCancelService가 한다.
    - prepare: 주문 행을 잠근 채 멱등성·취소 가능 상태·진행 중인 결제를 확인한다. 결제 전 주문은 여기서 취소를 끝내고,
      결제 후 주문은 취소 요청을 주문에 기록하고 payment_cancellations에 REQUESTED를 저장한다.
    - applyResult: 주문 → 결제 → 취소 기록 순서로 행을 잠그고 PG 취소 결과를 결제·주문·예약·재고에 반영한다.
 */
@Service
@RequiredArgsConstructor
public class OrderCancelTransactionService {

    private static final List<PaymentCancellationStatus> IN_PROGRESS =
            List.of(PaymentCancellationStatus.REQUESTED, PaymentCancellationStatus.UNKNOWN);

    private final OrderPaymentService orderPaymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentCancellationRepository cancellationRepository;

    @Transactional
    public CancelPreparation prepare(
            long buyerId,
            UUID orderId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash,
            OrderCancelRequest request,
            OffsetDateTime now
    ) {
        CancelableOrder order = orderPaymentService.lockForCancel(buyerId, orderId);

        // 멱등성 확인을 상태 검증보다 먼저 한다. 취소가 끝난 뒤의 재전송도 409가 아니라 최초 결과를 받아야 한다.
        if (order.hasCancelRequest()) {
            return replay(order, idempotencyKey, requestHash);
        }
        if (!order.cancelable()) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        if (order.status() == OrderStatus.PAYMENT_PENDING) {
            return new CancelPreparation.Done(cancelUnpaid(order, idempotencyKey, requestHash, request, now));
        }

        Payment payment = approvedPayment(order);
        orderPaymentService.requestPaidCancel(order, idempotencyKey.value(), requestHash.value(), request.reason());
        PaymentCancellation cancellation = cancellationRepository.save(PaymentCancellation.requestOrderCancel(
                payment.getId(), buyerId, payment.getAmount(), request.reason()));
        return new CancelPreparation.RequestPgCancel(cancellation.getId(), cancelCommand(payment, cancellation));
    }

    /**
     * PG 결제 취소 결과를 반영한다. 다른 요청이 이미 결과를 확정했으면 바꾸지 않고 확정된 결과를 돌려준다.
     */
    @Transactional
    public CancelOutcome applyResult(Long cancellationId, PaymentCancelResult result, OffsetDateTime now) {
        Long paymentId = cancellationRepository.findPaymentIdById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("결제 취소 요청이 없습니다: " + cancellationId));
        Long orderId = paymentRepository.findOrderIdById(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제 시도가 없습니다: " + paymentId));
        CancelableOrder order = orderPaymentService.lockForCancelResult(orderId);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
        PaymentCancellation cancellation = cancellationRepository.findByIdForUpdate(cancellationId).orElseThrow();
        if (!cancellation.isInProgress()) {
            return decided(order, cancellation);
        }

        return switch (result.outcome()) {
            case CANCELED -> {
                cancellation.succeed(result.transactionKey(), now);
                payment.cancel(result.canceledAt());
                CancelableOrder canceled = orderPaymentService.cancelPaid(order, now);
                yield CancelOutcome.completed(OrderCancelResponse.of(canceled, RefundStatus.SUCCEEDED));
            }
            case REJECTED -> {
                cancellation.fail(result.failureCode(), now);
                orderPaymentService.clearCancelRequest(order);
                yield CancelOutcome.rejection();
            }
            case UNKNOWN -> {
                cancellation.markUnknown(result.failureCode(), now);
                yield CancelOutcome.completed(OrderCancelResponse.of(order, RefundStatus.UNKNOWN));
            }
        };
    }

    private CancelPreparation replay(CancelableOrder order, IdempotencyKey idempotencyKey, RequestHash requestHash) {
        if (!idempotencyKey.value().equals(order.cancelIdempotencyKey())) {
            // 다른 키로 이미 취소된 주문은 더 취소할 수 없고, 결제 취소를 진행 중인 주문은 그 요청이 끝나야 한다.
            throw order.status() == OrderStatus.CANCELED
                    ? new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE)
                    : new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }
        if (!requestHash.value().equals(order.cancelRequestHash())) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
        }
        if (order.status() == OrderStatus.CANCELED) {
            return new CancelPreparation.Done(OrderCancelResponse.of(order, refundStatusOfCanceled(order)));
        }

        // 결제 취소가 진행·확인 중이다. 같은 서버 멱등 키로 다시 요청하면 PG가 최초 결과를 돌려주므로 중복 취소되지 않는다.
        PaymentCancellation cancellation = cancellationRepository
                .findOfOrder(order.id(), PaymentCancellationPurpose.ORDER_CANCEL, IN_PROGRESS).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("취소 요청이 기록된 주문에 진행 중인 결제 취소가 없습니다: " + order.id()));
        Payment payment = paymentRepository.findById(cancellation.getPaymentId()).orElseThrow();
        return new CancelPreparation.RequestPgCancel(cancellation.getId(), cancelCommand(payment, cancellation));
    }

    // 승인 응답을 기다리는 결제는 곧 결과가 나오므로 거부한다. 결과 불명(UNKNOWN) 결제는 막지 않고,
    // 이후 승인이 확인되면 결제 대기가 아닌 주문의 승인으로 보정 대상이 된다(ERD.md 3.3).
    private OrderCancelResponse cancelUnpaid(
            CancelableOrder order,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash,
            OrderCancelRequest request,
            OffsetDateTime now
    ) {
        if (paymentRepository.existsByOrderIdAndStatus(order.id(), PaymentStatus.PENDING)) {
            throw new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }
        CancelableOrder canceled = orderPaymentService.cancelUnpaid(
                order, idempotencyKey.value(), requestHash.value(), request.reason(), now);
        return OrderCancelResponse.of(canceled, RefundStatus.NONE);
    }

    // 결제 완료 주문에는 주문을 확정한 승인 결제가 정확히 하나 있다. 없으면 원장이 어긋난 것이다.
    private Payment approvedPayment(CancelableOrder order) {
        List<Payment> approved = paymentRepository.findByOrderIdAndStatusInOrderByIdAsc(
                order.id(), List.of(PaymentStatus.SUCCEEDED));
        if (approved.size() != 1) {
            throw new IllegalStateException("결제 완료 주문의 승인 결제가 하나가 아닙니다: orderId="
                    + order.id() + ", count=" + approved.size());
        }
        return approved.get(0);
    }

    private RefundStatus refundStatusOfCanceled(CancelableOrder order) {
        boolean refunded = !cancellationRepository.findOfOrder(order.id(), PaymentCancellationPurpose.ORDER_CANCEL,
                List.of(PaymentCancellationStatus.SUCCEEDED)).isEmpty();
        return refunded ? RefundStatus.SUCCEEDED : RefundStatus.NONE;
    }

    private CancelOutcome decided(CancelableOrder order, PaymentCancellation cancellation) {
        return switch (cancellation.getStatus()) {
            case SUCCEEDED -> CancelOutcome.completed(OrderCancelResponse.of(order, RefundStatus.SUCCEEDED));
            case FAILED -> CancelOutcome.rejection();
            case REQUESTED, UNKNOWN -> throw new IllegalStateException("진행 중인 취소는 확정된 결과가 아닙니다.");
        };
    }

    private static PaymentCancelCommand cancelCommand(Payment payment, PaymentCancellation cancellation) {
        return new PaymentCancelCommand(
                payment.getProviderPaymentId(), cancellation.getReason(), cancellation.getIdempotencyKey());
    }

    /**
     * PG 결제 취소 결과를 반영한 결과. 거절은 트랜잭션을 커밋한 뒤 호출하는 쪽이 오류로 응답한다.
     * 트랜잭션 안에서 예외를 던지면 취소 기록(FAILED)과 주문의 취소 요청 해제까지 롤백되기 때문이다.
     */
    public record CancelOutcome(OrderCancelResponse response, boolean rejected) {

        static CancelOutcome completed(OrderCancelResponse response) {
            return new CancelOutcome(response, false);
        }

        static CancelOutcome rejection() {
            return new CancelOutcome(null, true);
        }
    }
}
