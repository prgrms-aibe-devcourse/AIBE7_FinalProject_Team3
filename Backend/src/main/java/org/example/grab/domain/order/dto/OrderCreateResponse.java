package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderCreateResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        List<Item> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        OffsetDateTime paymentExpiresAt
) {

    public static OrderCreateResponse of(Order order, List<OrderItem> orderItems) {
        List<Item> items = orderItems.stream()
                .map(item -> new Item(
                        item.getOptionId(),
                        order.getProductNameSnapshot(),
                        item.getOptionNameSnapshot(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        Math.multiplyExact(item.getUnitPrice(), item.getQuantity())
                ))
                .toList();
        return new OrderCreateResponse(
                order.getUuid(),
                order.getOrderNumber(),
                order.getStatus(),
                items,
                order.getItemsAmount(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                order.getPaymentExpiresAt()
        );
    }

    public record Item(
            Long optionId,
            String productName,
            String optionName,
            long unitPrice,
            int quantity,
            long subtotal
    ) {
    }
}
