package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;

public record OrderStatusResponse(Long orderId, OrderStatus status) {

    public static OrderStatusResponse from(Order order) {
        return new OrderStatusResponse(order.getId(), order.getStatus());
    }
}
