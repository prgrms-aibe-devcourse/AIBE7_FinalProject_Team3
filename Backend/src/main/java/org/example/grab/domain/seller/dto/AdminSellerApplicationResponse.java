package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.SellerStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 운영자 심사 목록 항목(SELLER.md 1.4)
public record AdminSellerApplicationResponse(
        UUID applicationId,
        UUID userId,
        String brandName,
        String contactEmail,
        String businessRegistrationNumber,
        SellerStatus status,
        OffsetDateTime appliedAt
) {
}
