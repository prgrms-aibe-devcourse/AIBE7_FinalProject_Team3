package org.example.grab.domain.payment.entity;

// 결제 취소를 요청한 이유
public enum PaymentCancellationPurpose {
    // 소비자 주문 취소(GR-24)
    ORDER_CANCEL,
    // 결제 마감 후 승인 등 주문을 확정하지 못한 승인의 환불(GR-65)
    LATE_APPROVAL_COMPENSATION
}
