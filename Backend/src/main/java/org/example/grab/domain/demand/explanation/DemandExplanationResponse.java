package org.example.grab.domain.demand.explanation;

import java.time.OffsetDateTime;

// 응답 계약은 DEMAND.md 3.1을 따른다. explanation은 GENERATED일 때만, generatedAt은 저장된 설명일 때만 있다
public record DemandExplanationResponse(
        ExplanationStatus status,
        DemandExplanation explanation,
        OffsetDateTime generatedAt
) {

    public static DemandExplanationResponse of(ExplanationStatus status) {
        return new DemandExplanationResponse(status, null, null);
    }
}
