package org.example.grab.domain.payment.entity;

// 결제 이벤트가 들어온 경로.
public enum PaymentEventSource {
    API,
    WEBHOOK,
    RECONCILIATION
}
