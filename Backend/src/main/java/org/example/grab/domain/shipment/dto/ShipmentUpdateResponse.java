package org.example.grab.domain.shipment.dto;

import java.util.UUID;

public record ShipmentUpdateResponse(
        UUID orderId,
        String status,
        String carrier,
        String trackingNumber
) {
}
