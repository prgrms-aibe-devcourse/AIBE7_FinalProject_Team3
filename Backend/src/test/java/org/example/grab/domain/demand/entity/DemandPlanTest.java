package org.example.grab.domain.demand.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemandPlanTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T05:00:00Z");

    @Test
    @DisplayName("새 계획은 DRAFT이고 결정 필드가 비어 있다")
    void draft_startsUndecided() {
        DemandPlan plan = draft();

        assertThat(plan.getStatus()).isEqualTo(DemandPlanStatus.DRAFT);
        assertThat(plan.isDecided()).isFalse();
        assertThat(plan.getDecidedAt()).isNull();
        assertThat(plan.getUuid()).isNotNull();
    }

    @Test
    @DisplayName("MODIFIED 결정은 판매자가 정한 수량·사유·시각을 남긴다")
    void decide_recordsModified() {
        // given
        DemandPlan plan = draft();

        // when
        plan.decide(DemandPlanStatus.MODIFIED, "[{\"optionId\":1,\"quantity\":5}]", "재고 부담", NOW);

        // then
        assertThat(plan.getStatus()).isEqualTo(DemandPlanStatus.MODIFIED);
        assertThat(plan.getDecidedQuantities()).isEqualTo("[{\"optionId\":1,\"quantity\":5}]");
        assertThat(plan.getDecisionReason()).isEqualTo("재고 부담");
        assertThat(plan.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("결정은 한 번만 기록할 수 있다")
    void decide_rejectsSecondDecision() {
        // given
        DemandPlan plan = draft();
        plan.decide(DemandPlanStatus.ACCEPTED, null, null, NOW);

        // when & then
        assertThatThrownBy(() -> plan.decide(DemandPlanStatus.REJECTED, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(plan.getStatus()).isEqualTo(DemandPlanStatus.ACCEPTED);
    }

    @Test
    @DisplayName("수량은 MODIFIED에만 기록하고 MODIFIED에는 수량이 꼭 있어야 한다")
    void decide_requiresQuantitiesOnlyForModified() {
        assertThatThrownBy(() -> draft().decide(DemandPlanStatus.ACCEPTED, "[]", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().decide(DemandPlanStatus.MODIFIED, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().decide(DemandPlanStatus.DRAFT, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설명 결과는 PENDING에서 한 번만 기록하고 결정한 계획에도 기록한다")
    void recordExplanation_recordsOnceFromPending() {
        // given
        DemandPlan plan = DemandPlan.draft(100L, 7L, "{}", "OWN", "{}", "v1", ExplanationStatus.PENDING);
        plan.decide(DemandPlanStatus.REJECTED, null, null, NOW);

        // when
        boolean first = plan.recordExplanation(ExplanationStatus.GENERATED, "{\"summary\":\"요약\"}",
                "gemini-2.5-flash", "v1", 812);
        boolean second = plan.recordExplanation(ExplanationStatus.FAILED, null, null, "v1", null);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(plan.getExplanationStatus()).isEqualTo(ExplanationStatus.GENERATED);
        assertThat(plan.getExplanation()).isEqualTo("{\"summary\":\"요약\"}");
        assertThat(plan.getLlmModel()).isEqualTo("gemini-2.5-flash");
        assertThat(plan.getExplanationTokens()).isEqualTo(812);
    }

    @Test
    @DisplayName("설명이 꺼진 계획에는 기록하지 않고, 설명은 GENERATED에만 저장한다")
    void recordExplanation_rejectsInvalidRecord() {
        assertThat(draft().recordExplanation(ExplanationStatus.GENERATED, "{}", "m", "v1", 1)).isFalse();

        DemandPlan pending = DemandPlan.draft(100L, 7L, "{}", "OWN", "{}", "v1", ExplanationStatus.PENDING);
        assertThatThrownBy(() -> pending.recordExplanation(ExplanationStatus.DISCARDED, "{}", "m", "v1", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.recordExplanation(ExplanationStatus.DISABLED, null, null, "v1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DemandPlan draft() {
        return DemandPlan.draft(100L, 7L, "{}", "OWN", "{}", "v1", ExplanationStatus.DISABLED);
    }
}
