package org.example.grab.domain.demand.entity;

// DISABLED는 설명 생성이 꺼져 있어 요청하지 않은 계획이다(DEMAND.md 2.3)
public enum ExplanationStatus {
    PENDING,
    GENERATED,
    DISCARDED,
    FAILED,
    DISABLED
}
