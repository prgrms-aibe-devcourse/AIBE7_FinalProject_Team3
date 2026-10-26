package org.example.grab.domain.demand.dto;

import org.example.grab.domain.demand.entity.DemandPlanStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 계획 목록의 한 줄(DEMAND.md 2.4). recommended는 저장 시점 스냅샷의 추천 수량이며 계산하지 못한 계획은 null이다.
public record DemandPlanSummaryResponse(
        UUID planId,
        DemandPlanStatus status,
        Integer recommended,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt
) {
}
