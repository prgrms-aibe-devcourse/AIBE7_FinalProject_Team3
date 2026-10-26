package org.example.grab.domain.demand.explanation;

import java.util.UUID;

// 설명을 요청할 계획. input은 저장 시점 계산 결과로 만든 LLM 입력(DemandExplanationPrompt)이다
public record DemandPlanSavedEvent(UUID planId, String input) {
}
