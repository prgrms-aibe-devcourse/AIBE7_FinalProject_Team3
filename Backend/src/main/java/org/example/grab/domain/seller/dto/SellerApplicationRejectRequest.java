package org.example.grab.domain.seller.dto;

import jakarta.validation.constraints.NotBlank;

// 판매자 신청 반려 요청(SELLER.md 1.6). 사유 누락은 400 VALIDATION_FAILED
public record SellerApplicationRejectRequest(
        @NotBlank String reason
) {
}
