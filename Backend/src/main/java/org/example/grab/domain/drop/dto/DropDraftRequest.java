package org.example.grab.domain.drop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public record DropDraftRequest(
        @Size(max = 200) String name,
        String description,
        List<@NotBlank @Size(max = 500) String> imageUrls,
        @Positive Long categoryId,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        @Valid ShippingRequest shipping,
        List<@Valid OptionGroupRequest> optionGroups,
        List<@Valid OptionRequest> options
) {
}
