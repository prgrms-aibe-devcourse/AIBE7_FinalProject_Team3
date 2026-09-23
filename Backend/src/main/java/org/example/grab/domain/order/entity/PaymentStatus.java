package org.example.grab.domain.order.entity;

// 주문에 연결된 결제 시도의 상태를 정의한다.
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CANCELED
}
