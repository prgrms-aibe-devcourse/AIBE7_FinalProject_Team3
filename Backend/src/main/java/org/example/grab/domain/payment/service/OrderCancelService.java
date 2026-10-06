package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.CancelableOrder;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.idempotency.RequestHashGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/*
    소비자 주문 취소(ORDER.md 1.4, ERD.md 3.3). 결제 전 취소는 결제 상태 확인과 함께 한 트랜잭션에서 끝나고,
    결제 후 취소는 PG 결제 취소가 필요하므로 결제 도메인이 처리한다. 주문·예약·재고 변경은 OrderPaymentService를 거친다.
 */
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderPaymentService orderPaymentService;
    private final PaymentRepository paymentRepository;
    private final RequestHashGenerator requestHashGenerator;

    @Transactional
    public OrderCancelResponse cancel(long buyerId, UUID orderId, String idempotencyKeyHeader, OrderCancelRequest request) {
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyHeader);
        RequestHash requestHash = requestHashGenerator.generate(request);
        CancelableOrder order = orderPaymentService.lockForCancel(buyerId, orderId);

        // 멱등성 확인을 상태 검증보다 먼저 한다. 취소가 끝난 뒤의 재전송도 409가 아니라 최초 결과를 받아야 한다.
        if (order.hasCancelRequest()) {
            return replay(order, idempotencyKey, requestHash);
        }
        if (!order.cancelable()) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            // 결제 후 취소(토스 결제 취소)는 아직 지원하지 않는다. GR-24 후속 커밋에서 구현한다.
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        // 승인 응답을 기다리는 결제는 곧 결과가 나오므로 거부한다. 결과 불명(UNKNOWN) 결제는 막지 않고,
        // 이후 승인이 확인되면 결제 대기가 아닌 주문의 승인으로 보정 대상이 된다(ERD.md 3.3).
        if (paymentRepository.existsByOrderIdAndStatus(order.id(), PaymentStatus.PENDING)) {
            throw new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }

        CancelableOrder canceled = orderPaymentService.cancelUnpaid(
                order, idempotencyKey.value(), requestHash.value(), request.reason(), now());
        return OrderCancelResponse.of(canceled, RefundStatus.NONE);
    }

    private OrderCancelResponse replay(CancelableOrder order, IdempotencyKey idempotencyKey, RequestHash requestHash) {
        if (!idempotencyKey.value().equals(order.cancelIdempotencyKey())) {
            // 다른 키로 이미 취소된 주문은 더 취소할 수 없고, 결제 취소를 진행 중인 주문은 그 요청이 끝나야 한다.
            throw order.status() == OrderStatus.CANCELED
                    ? new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE)
                    : new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }
        if (!requestHash.value().equals(order.cancelRequestHash())) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
        }
        return OrderCancelResponse.of(order, RefundStatus.NONE);
    }

    // PostgreSQL TIMESTAMPTZ 정밀도(마이크로초)와 UTC로 맞춰, 최초 응답과 재전송 응답의 canceledAt이 같게 한다.
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
