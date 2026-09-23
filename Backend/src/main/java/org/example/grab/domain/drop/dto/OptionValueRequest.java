package org.example.grab.domain.drop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record OptionValueRequest(
        @NotBlank String key,
        @NotBlank @Size(max = 100) String value,
        @PositiveOrZero Integer sortOrder
) {
}
