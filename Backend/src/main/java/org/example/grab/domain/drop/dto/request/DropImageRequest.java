package org.example.grab.domain.drop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DropImageRequest(
        @NotNull UUID imageId,
        @NotBlank @Size(max = 500) String imageUrl
) {
}
