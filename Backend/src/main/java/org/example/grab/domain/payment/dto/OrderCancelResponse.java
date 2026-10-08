package org.example.grab.domain.payment.dto;

import org.example.grab.domain.order.dto.CancelableOrder;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 소비자 주문 취소 결과(ORDER.md 1.4). 결제 취소 결과를 확인 중이면 status는 바뀌지 않고 canceledAt은 null이다.
 */
public record OrderCancelResponse(
        UUID orderId,
        OrderStatus status,
        RefundStatus refundStatus,
        OffsetDateTime canceledAt
) {

    public static OrderCancelResponse of(CancelableOrder order, RefundStatus refundStatus) {
        return new OrderCancelResponse(order.orderId(), order.status(), refundStatus, order.canceledAt());
    }
}
