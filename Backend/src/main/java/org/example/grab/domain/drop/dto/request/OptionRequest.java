package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record OptionRequest(
        List<@Valid SelectionRequest> selections,
        @NotNull @PositiveOrZero Long unitPrice,
        @NotNull @PositiveOrZero Integer totalQuantity,
        Boolean active,
        @PositiveOrZero Integer sortOrder
) {
}
