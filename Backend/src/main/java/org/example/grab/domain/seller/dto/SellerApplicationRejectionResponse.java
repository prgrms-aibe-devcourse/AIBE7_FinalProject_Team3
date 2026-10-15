package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.SellerStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 판매자 신청 반려 응답(SELLER.md 1.6)
public record SellerApplicationRejectionResponse(
        UUID applicationId,
        SellerStatus status,
        String rejectedReason,
        OffsetDateTime rejectedAt
) {
}
