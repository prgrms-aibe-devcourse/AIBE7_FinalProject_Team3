package org.example.grab.domain.drop.dto;

import jakarta.validation.constraints.NotBlank;

public record SelectionRequest(
        @NotBlank String groupKey,
        @NotBlank String valueKey
) {
}
