package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MyOrderDetailResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        List<Item> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        String paymentStatus,
        OffsetDateTime paymentExpiresAt,
        Shipping shipping,
        OffsetDateTime orderedAt
) {

    public static MyOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, String paymentStatus, Shipment shipment) {
        List<Item> items = orderItems.stream()
                .map(item -> new Item(
                        order.getProductNameSnapshot(),
                        item.getOptionNameSnapshot(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        Math.multiplyExact(item.getUnitPrice(), item.getQuantity())))
                .toList();
        // 배송 상태는 orders.status로 관리하므로 배송 정보가 등록된 뒤에만 주문 상태를 배송 상태로 노출한다.
        Shipping shipping = shipment == null
                ? new Shipping(null, null, null)
                : new Shipping(order.getStatus().name(), shipment.getCarrierCode(), shipment.getTrackingNumber());

        return new MyOrderDetailResponse(
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                items,
                order.getItemsAmount(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                paymentStatus,
                order.getPaymentExpiresAt(),
                shipping,
                order.getCreatedAt()
        );
    }

    public record Item(
            String productName,
            String optionName,
            long unitPrice,
            int quantity,
            long subtotal
    ) {
    }

    public record Shipping(
            String status,
            String carrier,
            String trackingNumber
    ) {
    }
}
