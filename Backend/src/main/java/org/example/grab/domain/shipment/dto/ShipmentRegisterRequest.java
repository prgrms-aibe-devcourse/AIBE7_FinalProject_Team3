package org.example.grab.domain.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipmentRegisterRequest(
        @NotBlank @Size(max = 50) String carrier,
        @NotBlank @Size(max = 100) String trackingNumber
) {
}
