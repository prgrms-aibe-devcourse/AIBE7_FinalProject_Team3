package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 주문 취소를 처리하는 결제 도메인에 넘기는 주문 정보. 주문 엔티티를 도메인 밖으로 내보내지 않기 위한 읽기 전용 값이다.
 *
 * @param id                   주문 내부 ID(payments.order_id)
 * @param orderId              외부 노출 주문 ID
 * @param cancelable           소비자 취소 가능 상태인지(Order.isCancelable)
 * @param cancelIdempotencyKey 진행 중이거나 완료된 취소 요청의 키. 없으면 null
 */
public record CancelableOrder(
        Long id,
        UUID orderId,
        OrderStatus status,
        boolean cancelable,
        String cancelIdempotencyKey,
        String cancelRequestHash,
        OffsetDateTime canceledAt
) {

    public static CancelableOrder from(Order order) {
        return new CancelableOrder(
                order.getId(),
                order.getUuid(),
                order.getStatus(),
                order.isCancelable(),
                order.getCancelIdempotencyKey(),
                order.getCancelRequestHash(),
                order.getCanceledAt()
        );
    }

    public boolean hasCancelRequest() {
        return cancelIdempotencyKey != null;
    }
}
