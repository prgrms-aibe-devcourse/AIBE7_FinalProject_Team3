package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.SellerStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 판매자 신청 승인 응답(SELLER.md 1.5)
public record SellerApplicationApprovalResponse(
        UUID applicationId,
        SellerStatus status,
        OffsetDateTime approvedAt
) {
}
