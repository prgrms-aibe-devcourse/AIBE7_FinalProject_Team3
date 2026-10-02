package org.example.grab.domain.drop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DropCancelRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
