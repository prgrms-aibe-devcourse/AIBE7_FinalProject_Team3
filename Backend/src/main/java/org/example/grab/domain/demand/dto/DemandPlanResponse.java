package org.example.grab.domain.demand.dto;

import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest.DecidedQuantity;
import org.example.grab.domain.demand.entity.DemandPlanStatus;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 응답 계약은 DEMAND.md 2.5를 따른다. analysis는 저장 시점의 2.1 응답 스냅샷이다.
public record DemandPlanResponse(
        UUID planId,
        long dropId,
        DemandPlanStatus status,
        OffsetDateTime createdAt,
        DemandAnalysisResponse analysis,
        Decision decision,
        ExplanationStatus explanationStatus,
        JsonNode explanation
) {

    public record Decision(
            DemandPlanStatus status,
            List<DecidedQuantity> quantities,
            String reason,
            OffsetDateTime decidedAt
    ) {
    }
}
