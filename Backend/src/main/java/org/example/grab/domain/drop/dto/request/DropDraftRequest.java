package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public record DropDraftRequest(
        @Size(max = 200) String name,
        String description,
        @Size(max = 10) List<@NotNull @Valid DropImageRequest> images,
        @Positive Long categoryId,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        @Valid ShippingRequest shipping,
        List<@NotNull @Valid OptionGroupRequest> optionGroups,
        List<@NotNull @Valid OptionRequest> options
) {
}
