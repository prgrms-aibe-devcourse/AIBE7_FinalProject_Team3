package org.example.grab.domain.demand.explanation;

import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.domain.demand.repository.DemandPlanRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/*
    설명 생성 결과를 계획에 기록한다. 결정 기록과 같은 행을 고치므로 같은 행 잠금을 잡아
    동시에 커밋돼도 한쪽 변경이 다른 쪽에 덮이지 않게 한다(ERD.md 1.7).
 */
@Component
public class DemandExplanationRecorder {

    private final DemandPlanRepository demandPlanRepository;

    public DemandExplanationRecorder(DemandPlanRepository demandPlanRepository) {
        this.demandPlanRepository = demandPlanRepository;
    }

    @Transactional
    public boolean record(UUID planId, ExplanationStatus outcome, String explanationJson, String model,
                          Integer tokens) {
        return demandPlanRepository.findByUuidForUpdate(planId)
                .map(plan -> plan.recordExplanation(
                        outcome, explanationJson, model, DemandExplanationPrompt.VERSION, tokens))
                .orElse(false);
    }
}
