package org.example.grab.domain.drop.dto.response.common;

import org.example.grab.domain.drop.entity.Drop;

public record DropShippingResponse(Long shippingFee, String shippingNotice) {

    public static DropShippingResponse from(Drop drop) {
        return new DropShippingResponse(drop.getShippingFee(), drop.getShippingNotice());
    }
}
