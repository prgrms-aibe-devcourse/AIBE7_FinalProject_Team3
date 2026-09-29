package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.shipment.entity.Shipment;

// 구매자·판매자 주문 상세가 함께 쓰는 배송 정보. 두 화면의 배송 상태 규칙이 어긋나지 않도록 한곳에서 만든다.
public record OrderShippingResponse(
        String status,
        String carrier,
        String trackingNumber
) {

    public static OrderShippingResponse from(Order order, Shipment shipment) {
        // 배송 상태는 orders.status로 관리하므로 배송 정보가 등록된 뒤에만 주문 상태를 배송 상태로 노출한다.
        if (shipment == null) {
            return new OrderShippingResponse(null, null, null);
        }
        return new OrderShippingResponse(
                order.getStatus().name(), shipment.getCarrierCode(), shipment.getTrackingNumber());
    }
}
