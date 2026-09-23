package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingAddress {

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 30)
    private String recipientPhone;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 300)
    private String addressLine1;

    @Column(name = "address_line2", length = 300)
    private String addressLine2;

    @Column(name = "delivery_memo", length = 300)
    private String deliveryMemo;

    private ShippingAddress(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String deliveryMemo
    ) {
        this.recipientName = Objects.requireNonNull(recipientName);
        this.recipientPhone = Objects.requireNonNull(recipientPhone);
        this.postalCode = Objects.requireNonNull(postalCode);
        this.addressLine1 = Objects.requireNonNull(addressLine1);
        this.addressLine2 = addressLine2;
        this.deliveryMemo = deliveryMemo;
    }

    public static ShippingAddress of(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String deliveryMemo
    ) {
        return new ShippingAddress(
                recipientName,
                recipientPhone,
                postalCode,
                addressLine1,
                addressLine2,
                deliveryMemo
        );
    }

    @Override
    public String toString() {
        return "ShippingAddress{masked}";
    }
}
