package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 결제 도메인에 넘기는 주문 정보. 주문 엔티티를 도메인 밖으로 내보내지 않기 위한 읽기 전용 값이다.
 *
 * @param id          주문 내부 ID(payments.order_id)
 * @param orderId     외부 노출 주문 ID
 * @param orderNumber PG에 보내는 주문 식별자
 */
public record PayableOrder(
        Long id,
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        long totalAmount,
        OffsetDateTime paymentExpiresAt
) {

    public static PayableOrder from(Order order) {
        return new PayableOrder(
                order.getId(),
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentExpiresAt()
        );
    }

    public boolean isPaymentExpired(OffsetDateTime now) {
        return !now.isBefore(paymentExpiresAt);
    }
}
