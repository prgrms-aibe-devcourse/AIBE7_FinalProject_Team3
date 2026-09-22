package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;

public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalAmount,
        OffsetDateTime orderedAt
) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt()
        );
    }
}
