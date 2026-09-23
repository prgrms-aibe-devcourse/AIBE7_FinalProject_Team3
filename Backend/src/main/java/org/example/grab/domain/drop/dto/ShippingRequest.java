package org.example.grab.domain.drop.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record ShippingRequest(
        @PositiveOrZero Long shippingFee,
        String shippingNotice
) {
}
