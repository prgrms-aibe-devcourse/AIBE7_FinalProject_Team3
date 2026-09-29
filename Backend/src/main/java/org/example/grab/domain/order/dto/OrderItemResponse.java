package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;

import java.util.List;

// 구매자·판매자 주문 상세가 함께 쓰는 주문 항목. 상품 정보가 바뀌어도 주문 당시 스냅샷을 그대로 보여 준다.
public record OrderItemResponse(
        String productName,
        String optionName,
        long unitPrice,
        int quantity,
        long subtotal
) {

    public static List<OrderItemResponse> from(Order order, List<OrderItem> orderItems) {
        return orderItems.stream()
                .map(item -> new OrderItemResponse(
                        order.getProductNameSnapshot(),
                        item.getOptionNameSnapshot(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        Math.multiplyExact(item.getUnitPrice(), item.getQuantity())))
                .toList();
    }
}
