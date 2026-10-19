package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.SellerStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MySellerApplicationResponse(
        UUID applicationId,
        String brandName,
        String contactEmail,
        String businessRegistrationNumber,
        String description,
        SellerStatus status,
        OffsetDateTime appliedAt,
        String rejectedReason
) {
}
