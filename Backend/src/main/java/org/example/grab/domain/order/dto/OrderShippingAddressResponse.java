package org.example.grab.domain.order.dto;

import org.example.grab.domain.order.entity.ShippingAddress;

// 구매자·판매자 주문 상세가 함께 쓰는 배송지. 주문 생성 요청과 같은 필드 이름을 써서 클라이언트가 입력·조회에 같은 타입을 쓸 수 있게 한다.
public record OrderShippingAddressResponse(
        String recipient,
        String phone,
        String postalCode,
        String address1,
        String address2,
        String deliveryMemo
) {

    public static OrderShippingAddressResponse from(ShippingAddress address) {
        return new OrderShippingAddressResponse(
                address.getRecipientName(),
                address.getRecipientPhone(),
                address.getPostalCode(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getDeliveryMemo()
        );
    }
}
