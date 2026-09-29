package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.shipment.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SellerOrderDetailResponse(
        UUID orderId,
        String orderNumber,
        String status,
        List<OrderItemResponse> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        String paymentStatus,
        OrderShippingResponse shipping,
        OffsetDateTime orderedAt
) {

    public static SellerOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, String paymentStatus, Shipment shipment) {
        return new SellerOrderDetailResponse(
                order.getUuid(), order.getOrderNumber(), order.getStatus().name(),
                OrderItemResponse.from(order, orderItems),
                order.getItemsAmount(), order.getShippingAmount(), order.getTotalAmount(),
                paymentStatus, OrderShippingResponse.from(order, shipment), order.getCreatedAt());
    }
}
