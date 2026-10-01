package org.example.grab.domain.seller.entity;

public enum SellerStatus {
    PENDING, // 심사 대기
    APPROVED, // 승인. 이 상태일 때만 판매자 기능이 활성화된다(SELLER-002)
    REJECTED // 반려
}
