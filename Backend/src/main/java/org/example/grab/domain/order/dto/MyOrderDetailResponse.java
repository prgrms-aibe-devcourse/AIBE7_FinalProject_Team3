package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.shipment.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MyOrderDetailResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        List<OrderItemResponse> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        PaymentStatus paymentStatus,
        OffsetDateTime paymentExpiresAt,
        OrderShippingResponse shipping,
        OffsetDateTime orderedAt
) {

    public static MyOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, PaymentStatus paymentStatus, Shipment shipment) {
        // payment_expires_at은 결제 후에도 남아 있으므로 결제 대기 주문에서만 마감 시각으로 노출한다.
        OffsetDateTime paymentExpiresAt = order.getStatus() == OrderStatus.PAYMENT_PENDING
                ? order.getPaymentExpiresAt()
                : null;

        return new MyOrderDetailResponse(
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                OrderItemResponse.from(order, orderItems),
                order.getItemsAmount(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                paymentStatus,
                paymentExpiresAt,
                OrderShippingResponse.from(order, shipment),
                order.getCreatedAt()
        );
    }
}
