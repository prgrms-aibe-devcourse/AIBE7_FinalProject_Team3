package org.example.grab.domain.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OrderCreateRequest(
        @NotNull @Positive Long dropId,
        @NotEmpty @Valid List<Item> items,
        @NotNull @Valid ShippingAddress shippingAddress
) {

    public record Item(
            @NotNull @Positive Long optionId,
            @NotNull @Positive Integer quantity
    ) {
    }

    public record ShippingAddress(
            @NotBlank @Size(max = 100) String recipient,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 300) String address1,
            @Size(max = 300) String address2,
            @Size(max = 300) String deliveryMemo
    ) {
    }
}
