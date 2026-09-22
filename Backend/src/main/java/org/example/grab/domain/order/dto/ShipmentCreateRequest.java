package org.example.grab.domain.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ShipmentCreateRequest(
        @NotBlank @Size(max = 50) String carrier,
        @NotBlank @Size(max = 100) String trackingNumber,
        @NotNull OffsetDateTime shippedAt
) {
}
