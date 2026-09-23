package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SellerOrderDetailResponse(
        UUID orderId,
        String orderNumber,
        String status,
        List<ItemResponse> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        String paymentStatus,
        ShippingResponse shipping,
        OffsetDateTime orderedAt
) {

    public static SellerOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, String paymentStatus, Shipment shipment) {
        List<ItemResponse> items = orderItems.stream()
                .map(item -> new ItemResponse(
                        order.getProductNameSnapshot(),
                        item.getOptionNameSnapshot(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        Math.multiplyExact(item.getUnitPrice(), item.getQuantity())))
                .toList();
        ShippingResponse shipping = shipment == null
                ? new ShippingResponse(null, null, null)
                : new ShippingResponse(order.getStatus().name(), shipment.getCarrierCode(),
                        shipment.getTrackingNumber());

        return new SellerOrderDetailResponse(
                order.getUuid(), order.getOrderNumber(), order.getStatus().name(), items,
                order.getItemsAmount(), order.getShippingAmount(), order.getTotalAmount(),
                paymentStatus, shipping, order.getCreatedAt());
    }

    public record ItemResponse(
            String productName,
            String optionName,
            long unitPrice,
            int quantity,
            long subtotal
    ) {
    }

    public record ShippingResponse(
            String status,
            String carrier,
            String trackingNumber
    ) {
    }
}
