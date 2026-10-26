package org.example.grab.domain.demand.entity;

// DRAFT에서 결정 상태 하나로 한 번만 바뀐다(ERD.md 1.7)
public enum DemandPlanStatus {
    DRAFT,
    ACCEPTED,
    MODIFIED,
    REJECTED
}
