package org.example.grab.domain.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderCancelRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
