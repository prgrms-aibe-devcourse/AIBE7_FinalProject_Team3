package org.example.grab.domain.order.entity;

// 주문의 진행 상태를 정의한다.
public enum OrderStatus {
    PAYMENT_PENDING,
    PAID,
    EXPIRED,
    PREPARING,
    SHIPPED,
    DELIVERED,
    CANCELED
}
