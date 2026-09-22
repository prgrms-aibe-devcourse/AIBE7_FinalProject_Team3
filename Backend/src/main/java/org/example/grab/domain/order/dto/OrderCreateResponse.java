package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderCreateResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        List<ItemResponse> items,
        long itemsAmount,
        long shippingFee,
        long totalAmount,
        OffsetDateTime paymentExpiresAt
) {

    public static OrderCreateResponse from(Order order) {
        List<ItemResponse> items = order.getItems().stream()
                .map(item -> ItemResponse.from(order, item))
                .toList();
        return new OrderCreateResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                items,
                order.getItemsAmount(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                order.getPaymentExpiresAt()
        );
    }

    public record ItemResponse(
            Long optionId,
            String productName,
            String optionName,
            long unitPrice,
            int quantity,
            long subtotal
    ) {

        private static ItemResponse from(Order order, OrderItem item) {
            return new ItemResponse(
                    item.getOptionId(),
                    order.getProductNameSnapshot(),
                    item.getOptionNameSnapshot(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.calculateSubtotal()
            );
        }
    }
}
