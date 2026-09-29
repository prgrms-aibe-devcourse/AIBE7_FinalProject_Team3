package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.shipment.entity.Shipment;

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
        PaymentStatus paymentStatus,
        OffsetDateTime paymentExpiresAt,
        Shipping shipping,
        OffsetDateTime orderedAt
) {

    public static MyOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, PaymentStatus paymentStatus, Shipment shipment) {
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
        // payment_expires_at은 결제 후에도 남아 있으므로 결제 대기 주문에서만 마감 시각으로 노출한다.
        OffsetDateTime paymentExpiresAt = order.getStatus() == OrderStatus.PAYMENT_PENDING
                ? order.getPaymentExpiresAt()
                : null;

        return new MyOrderDetailResponse(
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                items,
                order.getItemsAmount(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                paymentStatus,
                paymentExpiresAt,
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
