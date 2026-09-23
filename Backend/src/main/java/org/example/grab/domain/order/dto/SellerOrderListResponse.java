package org.example.grab.domain.order.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

// 판매자 주문 목록의 한 건을 API 응답으로 표현한다.
public record SellerOrderListResponse(
        UUID orderId,
        String orderNumber,
        long dropId,
        String productName,
        String sellerName,
        String orderStatus,
        String paymentStatus,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        OffsetDateTime orderedAt
) {
}
