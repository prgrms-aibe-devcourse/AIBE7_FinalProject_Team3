package org.example.grab.domain.payment.dto;

// 주문 취소 응답의 결제 취소(환불) 상태(ORDER.md 1.4)
public enum RefundStatus {
    // 결제 전 취소라 환불할 결제가 없다
    NONE,
    // 결제 취소에 성공했다
    SUCCEEDED,
    // 결제 취소 결과를 확인 중이다. 같은 Idempotency-Key로 재전송해 확인한다
    UNKNOWN
}
