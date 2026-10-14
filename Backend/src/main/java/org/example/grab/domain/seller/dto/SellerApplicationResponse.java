package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.SellerStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SellerApplicationResponse(
        UUID applicationId,
        String brandName,
        SellerStatus status,
        OffsetDateTime appliedAt
) {
}
