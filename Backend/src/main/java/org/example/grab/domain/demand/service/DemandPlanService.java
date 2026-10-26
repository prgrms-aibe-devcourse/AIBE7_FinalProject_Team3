package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest.DecidedQuantity;
import org.example.grab.domain.demand.dto.DemandPlanResponse;
import org.example.grab.domain.demand.dto.DemandPlanSummaryResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/*
    판매 계획 저장·조회·결정(DEMAND.md 2.3~2.6). 결정은 기록만 하고 재고·가격·DROP 상태를 바꾸지 않는다.
    계획 내용은 저장 시점 스냅샷(JSONB)이라 이후 WISH·주문이 바뀌어도 저장한 계획은 그대로다.
 */
@Service
public class DemandPlanService {

    // 설명 생성이 이 시간 안에 끝나지 않으면 응답에서 실패로 보여 준다(저장값은 그대로)
    static final Duration EXPLANATION_TIMEOUT = Duration.ofMinutes(2);
    private static final TypeReference<List<DecidedQuantity>> DECIDED_QUANTITIES = new TypeReference<>() {
    };

    private final DemandAnalysisService demandAnalysisService;
    private final DemandAnalysisRepository demandAnalysisRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DemandPlanService(
            DemandAnalysisService demandAnalysisService,
            DemandAnalysisRepository demandAnalysisRepository,
            DemandPlanRepository demandPlanRepository,
            ObjectMapper objectMapper
    ) {
        this(demandAnalysisService, demandAnalysisRepository, demandPlanRepository, objectMapper, Clock.systemUTC());
    }

    DemandPlanService(
            DemandAnalysisService demandAnalysisService,
            DemandAnalysisRepository demandAnalysisRepository,
            DemandPlanRepository demandPlanRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.demandAnalysisService = demandAnalysisService;
        this.demandAnalysisRepository = demandAnalysisRepository;
        this.demandPlanRepository = demandPlanRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /*
        클라이언트가 보낸 계산 결과는 받지 않고 저장 시점 데이터로 다시 계산해 남긴다(DEMAND-005).
        권한·상태 확인은 계산(analyze)에서 함께 한다.
        LLM 설명(GR-146)이 아직 없어 설명 생성은 꺼진 상태(DISABLED)로 저장한다.
     */
    @Transactional
    public DemandPlanResponse save(long sellerId, long userId, long dropId, DemandCalculateRequest request) {
        DemandAnalysisResponse analysis = demandAnalysisService.analyze(sellerId, dropId, request);
        Assumption conversionRate = analysis.assumptions().conversionRate();
        DemandPlan plan = demandPlanRepository.save(DemandPlan.draft(
                dropId,
                userId,
                objectMapper.writeValueAsString(analysis.assumptions()),
                conversionRate == null ? null : conversionRate.source().name(),
                objectMapper.writeValueAsString(analysis),
                analysis.calculationVersion(),
                ExplanationStatus.DISABLED));
        return toResponse(plan, analysis, OffsetDateTime.now(clock));
    }

    // 계획 이력은 DROP 상태와 관계없이 본인 DROP이면 조회할 수 있다(DEMAND.md 2.4)
    @Transactional(readOnly = true)
    public PageResponse<DemandPlanSummaryResponse> findPlans(long sellerId, long dropId, int page, int size) {
        demandAnalysisService.findOwnedDrop(sellerId, dropId);
        return PageResponse.from(demandPlanRepository.findByDropIdLatestFirst(dropId, PageRequest.of(page, size))
                .map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public DemandPlanResponse findPlan(long sellerId, UUID planId) {
        DemandPlan plan = demandPlanRepository.findByUuid(planId)
                .orElseThrow(() -> new BusinessException(DemandErrorCode.DEMAND_PLAN_NOT_FOUND));
        findPlanDrop(sellerId, plan);
        return toResponse(plan, readAnalysis(plan), OffsetDateTime.now(clock));
    }

    /*
        계획 행을 잠그고 상태를 다시 확인해 동시 결정 중 하나만 성공시킨다(ERD.md 1.7).
        이미 결정한 계획이면 판매 시작 여부와 관계없이 그 사실을 먼저 알린다.
     */
    @Transactional
    public DemandPlanResponse decide(long sellerId, UUID planId, DemandPlanDecisionRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        DemandPlan plan = demandPlanRepository.findByUuidForUpdate(planId)
                .orElseThrow(() -> new BusinessException(DemandErrorCode.DEMAND_PLAN_NOT_FOUND));
        TargetDrop drop = findPlanDrop(sellerId, plan);
        if (plan.isDecided()) {
            throw new BusinessException(DemandErrorCode.DEMAND_PLAN_ALREADY_DECIDED);
        }
        DemandAnalysisService.requireAnalyzable(drop, now);

        DemandAnalysisResponse analysis = readAnalysis(plan);
        String decidedQuantities = null;
        if (request.status() == DemandPlanStatus.MODIFIED) {
            requireAllPlanOptions(analysis, request.quantities());
            decidedQuantities = objectMapper.writeValueAsString(request.quantities());
        }
        plan.decide(request.status(), decidedQuantities, request.reason(), now);
        return toResponse(plan, analysis, now);
    }

    // 다른 판매자의 계획은 존재 여부도 드러내지 않도록 없는 계획과 같이 응답한다(DEMAND.md 2.5)
    private TargetDrop findPlanDrop(long sellerId, DemandPlan plan) {
        return demandAnalysisRepository.findTargetDrop(plan.getDropId())
                .filter(drop -> drop.sellerId() == sellerId)
                .orElseThrow(() -> new BusinessException(DemandErrorCode.DEMAND_PLAN_NOT_FOUND));
    }

    // 판매자가 정한 수량은 계획의 모든 옵션을 한 번씩만 포함해야 한다(DEMAND.md 2.6)
    private static void requireAllPlanOptions(DemandAnalysisResponse analysis, List<DecidedQuantity> quantities) {
        Set<Long> planOptionIds = analysis.plan().options().stream()
                .map(OptionPlan::optionId)
                .collect(Collectors.toSet());
        Set<Long> requestedOptionIds = quantities.stream()
                .map(DecidedQuantity::optionId)
                .collect(Collectors.toSet());
        if (requestedOptionIds.size() != quantities.size() || !requestedOptionIds.equals(planOptionIds)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    // 스냅샷은 저장한 그대로 돌려준다. 기본 설정은 시각을 UTC로 바꿔 읽어 asOf(+09:00) 같은 값의 표기가 달라진다.
    private DemandAnalysisResponse readAnalysis(DemandPlan plan) {
        return objectMapper.readerFor(DemandAnalysisResponse.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(plan.getResult());
    }

    private DemandPlanSummaryResponse toSummary(DemandPlan plan) {
        JsonNode recommended = objectMapper.readTree(plan.getResult()).path("plan").path("recommended");
        return new DemandPlanSummaryResponse(
                plan.getUuid(),
                plan.getStatus(),
                recommended.isNumber() ? recommended.intValue() : null,
                plan.getCreatedAt(),
                plan.getDecidedAt());
    }

    private DemandPlanResponse toResponse(DemandPlan plan, DemandAnalysisResponse analysis, OffsetDateTime now) {
        DemandPlanResponse.Decision decision = plan.isDecided()
                ? new DemandPlanResponse.Decision(
                        plan.getStatus(),
                        plan.getDecidedQuantities() == null
                                ? null
                                : objectMapper.readValue(plan.getDecidedQuantities(), DECIDED_QUANTITIES),
                        plan.getDecisionReason(),
                        plan.getDecidedAt())
                : null;
        return new DemandPlanResponse(
                plan.getUuid(),
                plan.getDropId(),
                plan.getStatus(),
                plan.getCreatedAt(),
                analysis,
                decision,
                displayedExplanationStatus(plan, now),
                plan.getExplanation() == null ? null : objectMapper.readTree(plan.getExplanation()));
    }

    private static ExplanationStatus displayedExplanationStatus(DemandPlan plan, OffsetDateTime now) {
        if (plan.getExplanationStatus() == ExplanationStatus.PENDING
                && plan.getCreatedAt() != null
                && plan.getCreatedAt().plus(EXPLANATION_TIMEOUT).isBefore(now)) {
            return ExplanationStatus.FAILED;
        }
        return plan.getExplanationStatus();
    }
}
