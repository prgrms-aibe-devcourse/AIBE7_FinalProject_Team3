package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.shipment.entity.Shipment;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record SellerOrderDetailResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        List<OrderItemResponse> items,
        long itemsAmount,
        long shippingAmount,
        long totalAmount,
        PaymentStatus paymentStatus,
        OrderShippingAddressResponse shippingAddress,
        OrderShippingResponse shipping,
        OffsetDateTime orderedAt
) {

    // 구매자 개인정보 노출을 줄이기 위해 발송 처리에 필요한 상태에서만 배송지를 보여준다.
    private static final Set<OrderStatus> SHIPPING_ADDRESS_VISIBLE_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED);

    public static SellerOrderDetailResponse from(
            Order order, List<OrderItem> orderItems, PaymentStatus paymentStatus, Shipment shipment) {
        OrderShippingAddressResponse shippingAddress = SHIPPING_ADDRESS_VISIBLE_STATUSES.contains(order.getStatus())
                ? OrderShippingAddressResponse.from(order.getShippingAddress())
                : null;

        return new SellerOrderDetailResponse(
                order.getUuid(), order.getOrderNumber(), order.getStatus(),
                OrderItemResponse.from(order, orderItems),
                order.getItemsAmount(), order.getShippingAmount(), order.getTotalAmount(),
                paymentStatus, shippingAddress, OrderShippingResponse.from(order, shipment), order.getCreatedAt());
    }
}
