package org.example.grab.domain.demand.explanation;

import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationGenerator.Outcome;
import org.example.grab.domain.demand.repository.DemandExplanationRepository;
import org.example.grab.domain.demand.repository.DemandExplanationRepository.StoredExplanation;
import org.example.grab.domain.demand.service.DemandAnalysisService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/*
    판매자가 요청할 때 그 판매자가 고른 가정의 분석 결과를 설명한다(DEMAND.md 3절).
    - 같은 DROP·같은 입력(프롬프트 버전+입력 JSON의 해시)으로 만든 설명이 있으면 LLM을 부르지 않고 다시 쓴다.
    - LLM을 부를 때만 판매자별 하루 한도를 센다. 넘으면 DEMAND_EXPLANATION_LIMIT_EXCEEDED.
      생성 단계 추천 근거 설명(8절)과 같은 한도를 함께 센다.
    - 호출·시간 제한·검증은 DemandExplanationGenerator가 맡는다.
    - GENERATED·DISCARDED만 저장한다. FAILED는 남기지 않아 다음 요청에서 다시 시도한다.
    - LLM 응답을 기다리는 동안 DB 트랜잭션을 잡지 않는다(분석 계산과 저장은 각각 짧게 끝난다).
 */
@Service
public class DemandExplanationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 하루 한도 Redis 키 접두사(DailyRequestLimitRepository). 생성 단계 추천 근거 설명과 함께 쓴다
    static final String LIMIT_SCOPE = "demand:explanation";

    private final DemandAnalysisService demandAnalysisService;
    private final DemandExplanationGenerator generator;
    private final DemandExplanationRepository explanationRepository;
    private final DailyRequestLimitRepository limitRepository;
    private final DemandExplanationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DemandExplanationService(
            DemandAnalysisService demandAnalysisService,
            DemandExplanationGenerator generator,
            DemandExplanationRepository explanationRepository,
            DailyRequestLimitRepository limitRepository,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper
    ) {
        this(demandAnalysisService, generator, explanationRepository, limitRepository, properties, objectMapper,
                Clock.systemUTC());
    }

    DemandExplanationService(
            DemandAnalysisService demandAnalysisService,
            DemandExplanationGenerator generator,
            DemandExplanationRepository explanationRepository,
            DailyRequestLimitRepository limitRepository,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.demandAnalysisService = demandAnalysisService;
        this.generator = generator;
        this.explanationRepository = explanationRepository;
        this.limitRepository = limitRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DemandExplanationResponse explain(long sellerId, long dropId, DemandCalculateRequest request) {
        // 권한·소유권·상태 검사와 계산은 분석 조회와 같다(DEMAND.md 2절)
        DemandAnalysisResponse analysis = demandAnalysisService.analyze(sellerId, dropId, request);
        if (!generator.available()) {
            return DemandExplanationResponse.of(ExplanationStatus.DISABLED);
        }
        String input = DemandExplanationPrompt.input(analysis, objectMapper);
        String inputHash = inputHash(input);
        Optional<StoredExplanation> stored = explanationRepository.find(dropId, inputHash);
        if (stored.isPresent()) {
            generator.countReused(DemandExplanationGenerator.STAGE_WISH);
            return toResponse(stored.get());
        }
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate();
        long requests = limitRepository.increment(LIMIT_SCOPE, sellerId, today);
        if (requests > properties.dailyLimit()) {
            throw new BusinessException(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED);
        }
        Outcome outcome = generator.generate(DemandExplanationPrompt.SYSTEM, input,
                DemandExplanationGenerator.STAGE_WISH);
        if (outcome.status() == ExplanationStatus.FAILED) {
            return DemandExplanationResponse.of(ExplanationStatus.FAILED);
        }
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        explanationRepository.save(dropId, inputHash, outcome.status().name(),
                outcome.explanation() == null ? null : objectMapper.writeValueAsString(outcome.explanation()),
                outcome.model(), DemandExplanationPrompt.VERSION, outcome.tokens(), generatedAt);
        return new DemandExplanationResponse(outcome.status(), outcome.explanation(), generatedAt);
    }

    private DemandExplanationResponse toResponse(StoredExplanation stored) {
        ExplanationStatus status = ExplanationStatus.valueOf(stored.status());
        DemandExplanation explanation = stored.explanationJson() == null
                ? null
                : objectMapper.readValue(stored.explanationJson(), DemandExplanation.class);
        return new DemandExplanationResponse(status, explanation, stored.createdAt());
    }

    static String inputHash(String input) {
        return DemandExplanationGenerator.inputHash(DemandExplanationPrompt.VERSION, input);
    }
}
