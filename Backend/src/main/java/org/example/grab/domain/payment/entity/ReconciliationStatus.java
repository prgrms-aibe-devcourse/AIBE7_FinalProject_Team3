package org.example.grab.domain.payment.entity;

// 결제 결과를 PG와 다시 맞춰 봐야 하는지 나타낸다. 결제 상태와 분리해 관리한다(COMMON.md 2.5).
public enum ReconciliationStatus {
    NONE,
    REQUIRED,
    RESOLVED
}
