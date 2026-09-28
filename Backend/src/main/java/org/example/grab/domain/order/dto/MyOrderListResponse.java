package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MyOrderListResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        long totalAmount,
        OffsetDateTime orderedAt
) {

    public static MyOrderListResponse from(Order order) {
        return new MyOrderListResponse(
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt()
        );
    }
}
