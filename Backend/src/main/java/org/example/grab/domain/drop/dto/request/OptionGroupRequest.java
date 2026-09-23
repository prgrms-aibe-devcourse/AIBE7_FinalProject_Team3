package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OptionGroupRequest(
        @NotBlank String key,
        @NotBlank @Size(max = 100) String name,
        @PositiveOrZero Integer sortOrder,
        List<@Valid OptionValueRequest> values
) {
}
