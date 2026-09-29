package org.example.grab.domain.payment.entity;

// 결제 이벤트를 결제·주문에 반영한 결과.
public enum PaymentEventResult {
    APPLIED,
    REJECTED,
    RECONCILIATION_REQUIRED
}
