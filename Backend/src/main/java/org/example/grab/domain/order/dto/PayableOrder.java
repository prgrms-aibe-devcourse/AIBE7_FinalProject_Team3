package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
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

    private static final Set<OrderStatus> PAID_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

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

    // 결제가 끝나 배송 단계로 넘어간 주문인지. 이런 주문에 온 결제 요청은 이미 처리된 결제로 거부한다.
    public boolean isPaid() {
        return PAID_STATUSES.contains(status);
    }

    // 새 결제 승인과 승인 재요청을 받을 수 있는 주문인지. 두 경로가 같은 기준을 쓰도록 여기서만 판정한다.
    public boolean isPayable(OffsetDateTime now) {
        return status == OrderStatus.PAYMENT_PENDING && !isPaymentExpired(now);
    }

    // 결제 확정·만료와 같은 기준을 쓰도록 주문 엔티티의 판정을 그대로 따른다.
    public boolean isPaymentExpired(OffsetDateTime now) {
        return Order.isPaymentExpired(paymentExpiresAt, now);
    }
}
