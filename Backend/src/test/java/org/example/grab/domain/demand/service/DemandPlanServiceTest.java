package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumptions;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Conversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Demand;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.MarketConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OwnConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Plan;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Suggestion;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandDataGap;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest.DecidedQuantity;
import org.example.grab.domain.demand.dto.DemandPlanResponse;
import org.example.grab.domain.demand.dto.DemandPlanSummaryResponse;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.entity.DemandPlan;
import org.example.grab.domain.demand.entity.DemandPlanStatus;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.domain.demand.repository.DemandPlanRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DemandPlanServiceTest {

    private static final long SELLER_ID = 3L;
    private static final long USER_ID = 30L;
    private static final long DROP_ID = 100L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T05:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private DemandAnalysisService demandAnalysisService;
    private DemandAnalysisRepository demandAnalysisRepository;
    private DemandPlanRepository demandPlanRepository;
    private DemandPlanService service;

    @BeforeEach
    void setUp() {
        demandAnalysisService = mock(DemandAnalysisService.class);
        demandAnalysisRepository = mock(DemandAnalysisRepository.class);
        demandPlanRepository = mock(DemandPlanRepository.class);
        service = new DemandPlanService(demandAnalysisService, demandAnalysisRepository, demandPlanRepository,
                objectMapper, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        given(demandPlanRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("저장은 서버가 다시 계산한 결과를 스냅샷으로 남기고 설명 생성이 꺼진 DRAFT로 저장한다")
    void save_storesRecalculatedSnapshot() {
        // given
        DemandCalculateRequest request = new DemandCalculateRequest(null, null, new BigDecimal("0.05"));
        given(demandAnalysisService.analyze(SELLER_ID, DROP_ID, request)).willReturn(analysis());

        // when
        DemandPlanResponse response = service.save(SELLER_ID, USER_ID, DROP_ID, request);

        // then
        ArgumentCaptor<DemandPlan> saved = ArgumentCaptor.forClass(DemandPlan.class);
        verify(demandPlanRepository).save(saved.capture());
        DemandPlan plan = saved.getValue();
        assertThat(plan.getStatus()).isEqualTo(DemandPlanStatus.DRAFT);
        assertThat(plan.getExplanationStatus()).isEqualTo(ExplanationStatus.DISABLED);
        assertThat(plan.getConversionBasis()).isEqualTo("OWN");
        assertThat(plan.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(plan.getCalculationVersion()).isEqualTo("v1");
        assertThat(objectMapper.readValue(plan.getResult(), DemandAnalysisResponse.class)).isEqualTo(analysis());
        assertThat(objectMapper.readValue(plan.getAssumptions(), Assumptions.class)).isEqualTo(analysis().assumptions());
        assertThat(response.planId()).isEqualTo(plan.getUuid());
        assertThat(response.analysis()).isEqualTo(analysis());
        assertThat(response.decision()).isNull();
        assertThat(response.explanation()).isNull();
    }

    @Test
    @DisplayName("전환율 기준이 없어 계산하지 못한 계획은 기준 컬럼을 비워 저장한다")
    void save_storesNullBasisWithoutConversion() {
        // given
        DemandAnalysisResponse analysis = analysisWithoutConversion();
        given(demandAnalysisService.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults())).willReturn(analysis);

        // when
        service.save(SELLER_ID, USER_ID, DROP_ID, DemandCalculateRequest.defaults());

        // then
        ArgumentCaptor<DemandPlan> saved = ArgumentCaptor.forClass(DemandPlan.class);
        verify(demandPlanRepository).save(saved.capture());
        assertThat(saved.getValue().getConversionBasis()).isNull();
    }

    @Test
    @DisplayName("계획 목록은 본인 DROP을 확인하고 저장 시점 추천 수량을 요약한다")
    void findPlans_summarizesSnapshots() {
        // given
        DemandPlan calculated = savedPlan(analysis());
        DemandPlan uncalculated = savedPlan(analysisWithoutConversion());
        given(demandPlanRepository.findByDropIdLatestFirst(DROP_ID, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(calculated, uncalculated), PageRequest.of(0, 20), 2));

        // when
        PageResponse<DemandPlanSummaryResponse> page = service.findPlans(SELLER_ID, DROP_ID, 0, 20);

        // then
        verify(demandAnalysisService).findOwnedDrop(SELLER_ID, DROP_ID);
        assertThat(page.content()).extracting(DemandPlanSummaryResponse::recommended).containsExactly(44, null);
        assertThat(page.content()).extracting(DemandPlanSummaryResponse::planId)
                .containsExactly(calculated.getUuid(), uncalculated.getUuid());
    }

    @Test
    @DisplayName("다른 판매자의 계획은 없는 계획과 같이 DEMAND_PLAN_NOT_FOUND로 응답한다")
    void findPlan_hidesOtherSellersPlan() {
        // given
        DemandPlan plan = savedPlan(analysis());
        given(demandPlanRepository.findByUuid(plan.getUuid())).willReturn(Optional.of(plan));
        given(demandAnalysisRepository.findTargetDrop(DROP_ID)).willReturn(Optional.of(
                new TargetDrop(DROP_ID, SELLER_ID + 1, "WISH", 7L, NOW.plusDays(1))));

        // when & then
        assertThatThrownBy(() -> service.findPlan(SELLER_ID, plan.getUuid()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_PLAN_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 계획은 DEMAND_PLAN_NOT_FOUND로 응답한다")
    void findPlan_rejectsMissingPlan() {
        UUID planId = UUID.randomUUID();
        given(demandPlanRepository.findByUuid(planId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPlan(SELLER_ID, planId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_PLAN_NOT_FOUND);
    }

    @Test
    @DisplayName("설명 대기가 2분을 넘으면 응답에서만 FAILED로 보이고 그 전에는 PENDING이다")
    void findPlan_showsStalePendingAsFailed() {
        // given
        DemandPlan stale = savedPlan(analysis(), ExplanationStatus.PENDING, NOW.minusMinutes(2).minusSeconds(1));
        DemandPlan fresh = savedPlan(analysis(), ExplanationStatus.PENDING, NOW.minusMinutes(1));
        given(demandPlanRepository.findByUuid(stale.getUuid())).willReturn(Optional.of(stale));
        given(demandPlanRepository.findByUuid(fresh.getUuid())).willReturn(Optional.of(fresh));
        givenOwnedWishDrop();

        // when & then
        assertThat(service.findPlan(SELLER_ID, stale.getUuid()).explanationStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(stale.getExplanationStatus()).isEqualTo(ExplanationStatus.PENDING);
        assertThat(service.findPlan(SELLER_ID, fresh.getUuid()).explanationStatus()).isEqualTo(ExplanationStatus.PENDING);
    }

    @Test
    @DisplayName("MODIFIED 결정은 계획의 옵션별 수량을 기록하고 결정 내용을 응답한다")
    void decide_recordsModifiedQuantities() {
        // given
        DemandPlan plan = givenLockedPlan();
        List<DecidedQuantity> quantities = List.of(
                new DecidedQuantity(1001L, 10), new DecidedQuantity(1002L, 12), new DecidedQuantity(1003L, 0));

        // when
        DemandPlanResponse response = service.decide(SELLER_ID, plan.getUuid(),
                new DemandPlanDecisionRequest(DemandPlanStatus.MODIFIED, quantities, "재고 부담"));

        // then
        assertThat(plan.getStatus()).isEqualTo(DemandPlanStatus.MODIFIED);
        assertThat(response.decision()).isEqualTo(new DemandPlanResponse.Decision(
                DemandPlanStatus.MODIFIED, quantities, "재고 부담", NOW));
    }

    @Test
    @DisplayName("ACCEPTED 결정은 수량 없이 기록한다")
    void decide_recordsAccepted() {
        // given
        DemandPlan plan = givenLockedPlan();

        // when
        DemandPlanResponse response = service.decide(SELLER_ID, plan.getUuid(),
                new DemandPlanDecisionRequest(DemandPlanStatus.ACCEPTED, null, null));

        // then
        assertThat(plan.getDecidedQuantities()).isNull();
        assertThat(response.decision().quantities()).isNull();
        assertThat(response.decision().decidedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("MODIFIED 수량이 계획의 옵션과 다르거나 같은 옵션이 두 번 있으면 VALIDATION_FAILED로 거부한다")
    void decide_rejectsMismatchedOptions() {
        // given
        DemandPlan plan = givenLockedPlan();
        List<DecidedQuantity> missing = List.of(new DecidedQuantity(1001L, 10), new DecidedQuantity(1002L, 12));
        List<DecidedQuantity> duplicated = List.of(new DecidedQuantity(1001L, 10), new DecidedQuantity(1002L, 12),
                new DecidedQuantity(1003L, 1), new DecidedQuantity(1003L, 2));
        List<DecidedQuantity> unknown = List.of(new DecidedQuantity(1001L, 10), new DecidedQuantity(1002L, 12),
                new DecidedQuantity(9999L, 1));

        // when & then
        for (List<DecidedQuantity> quantities : List.of(missing, duplicated, unknown)) {
            assertThatThrownBy(() -> service.decide(SELLER_ID, plan.getUuid(),
                    new DemandPlanDecisionRequest(DemandPlanStatus.MODIFIED, quantities, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
        assertThat(plan.isDecided()).isFalse();
    }

    @Test
    @DisplayName("이미 결정한 계획은 DEMAND_PLAN_ALREADY_DECIDED로 거부한다")
    void decide_rejectsDecidedPlan() {
        // given
        DemandPlan plan = givenLockedPlan();
        plan.decide(DemandPlanStatus.REJECTED, null, null, NOW.minusHours(1));

        // when & then
        assertThatThrownBy(() -> service.decide(SELLER_ID, plan.getUuid(),
                new DemandPlanDecisionRequest(DemandPlanStatus.ACCEPTED, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_PLAN_ALREADY_DECIDED);
    }

    @Test
    @DisplayName("판매 시작 시각이 지나면 결정을 DEMAND_ANALYSIS_UNAVAILABLE로 거부한다")
    void decide_rejectsAfterSaleStart() {
        // given
        DemandPlan plan = savedPlan(analysis());
        given(demandPlanRepository.findByUuidForUpdate(plan.getUuid())).willReturn(Optional.of(plan));
        given(demandAnalysisRepository.findTargetDrop(DROP_ID)).willReturn(Optional.of(
                new TargetDrop(DROP_ID, SELLER_ID, "WISH", 7L, NOW)));

        // when & then
        assertThatThrownBy(() -> service.decide(SELLER_ID, plan.getUuid(),
                new DemandPlanDecisionRequest(DemandPlanStatus.ACCEPTED, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_ANALYSIS_UNAVAILABLE);
        assertThat(plan.isDecided()).isFalse();
    }

    private DemandPlan givenLockedPlan() {
        DemandPlan plan = savedPlan(analysis());
        given(demandPlanRepository.findByUuidForUpdate(plan.getUuid())).willReturn(Optional.of(plan));
        givenOwnedWishDrop();
        return plan;
    }

    private void givenOwnedWishDrop() {
        given(demandAnalysisRepository.findTargetDrop(DROP_ID)).willReturn(Optional.of(
                new TargetDrop(DROP_ID, SELLER_ID, "WISH", 7L, NOW.plusDays(1))));
    }

    private DemandPlan savedPlan(DemandAnalysisResponse analysis) {
        return savedPlan(analysis, ExplanationStatus.DISABLED, NOW.minusHours(1));
    }

    private DemandPlan savedPlan(DemandAnalysisResponse analysis, ExplanationStatus explanationStatus,
                                 OffsetDateTime createdAt) {
        DemandPlan plan = DemandPlan.draft(DROP_ID, USER_ID, objectMapper.writeValueAsString(analysis.assumptions()),
                "OWN", objectMapper.writeValueAsString(analysis), "v1", explanationStatus);
        ReflectionTestUtils.setField(plan, "createdAt", createdAt);
        return plan;
    }

    private static DemandAnalysisResponse analysis() {
        return new DemandAnalysisResponse(
                DROP_ID, NOW, "v1", new Suggestion(true),
                new Demand(100, 40, List.of(), true),
                new Conversion(SampleSelection.CATEGORY,
                        new OwnConversion(new BigDecimal("0.40"), new BigDecimal("0.30"), new BigDecimal("0.51"),
                                new BigDecimal("1.0"), ConversionConfidence.LOW, 3, 85),
                        MarketConversion.unavailable(OffsetDateTime.parse("2026-10-21T15:00:00Z"))),
                new Assumptions(
                        new Assumption(new BigDecimal("0.40"), AssumptionSource.OWN),
                        new Assumption(new BigDecimal("1.0"), AssumptionSource.OWN),
                        new Assumption(new BigDecimal("0.1"), AssumptionSource.DEFAULT)),
                new Plan(30, 40, 51, 44, 120, -76, List.of(
                        new OptionPlan(1001L, "블랙 / M", 40, 15),
                        new OptionPlan(1002L, "블랙 / L", 40, 15),
                        new OptionPlan(1003L, "화이트 / M", 40, 14))),
                List.of(DemandDataGap.SMALL_CONVERSION_SAMPLE),
                List.of(DemandAnalysisService.WISH_ONLY_NOTE));
    }

    private static DemandAnalysisResponse analysisWithoutConversion() {
        DemandAnalysisResponse base = analysis();
        return new DemandAnalysisResponse(
                DROP_ID, NOW, "v1", base.suggestion(), base.demand(), base.conversion(),
                new Assumptions(null, null, new Assumption(new BigDecimal("0.1"), AssumptionSource.DEFAULT)),
                new Plan(null, null, null, null, 120, null, List.of(new OptionPlan(1001L, "기본", 120, null))),
                List.of(DemandDataGap.NO_CONVERSION_SAMPLE),
                base.notes());
    }
}
