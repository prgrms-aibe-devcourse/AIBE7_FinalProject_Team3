package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        List<ItemResponse> items,
        long totalAmount,
        String paymentStatus,
        ShippingResponse shipping,
        OffsetDateTime orderedAt
) {

    public static OrderDetailResponse from(Order order, String paymentStatus, Shipment shipment) {
        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getItems().stream().map(item -> ItemResponse.from(order, item)).toList(),
                order.getTotalAmount(),
                paymentStatus,
                shipment == null ? null : ShippingResponse.from(order, shipment),
                order.getCreatedAt()
        );
    }

    public record ItemResponse(
            String productName,
            String optionName,
            long unitPrice,
            int quantity,
            long subtotal
    ) {

        private static ItemResponse from(Order order, OrderItem item) {
            return new ItemResponse(
                    order.getProductNameSnapshot(),
                    item.getOptionNameSnapshot(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.calculateSubtotal()
            );
        }
    }

    public record ShippingResponse(String status, String carrier, String trackingNumber) {

        private static ShippingResponse from(Order order, Shipment shipment) {
            return new ShippingResponse(
                    order.getStatus().name(),
                    shipment.getCarrierCode(),
                    shipment.getTrackingNumber()
            );
        }
    }
}
