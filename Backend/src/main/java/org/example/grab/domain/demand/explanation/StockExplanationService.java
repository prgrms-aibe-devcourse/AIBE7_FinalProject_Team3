package org.example.grab.domain.demand.explanation;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.dto.StockExplanationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationGenerator.Outcome;
import org.example.grab.domain.demand.service.SimilarSalesService;
import org.example.grab.domain.demand.service.StockRecommendationService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/*
    생성 단계 추천 근거 설명(DEMAND.md 8절). 판매자가 "근거 보기"를 누를 때만 부른다.
    - 같은 폼 입력으로 재고 추천(5.5)과 비슷한 DROP 실적(7.5)을 다시 계산해 LLM 입력을 만든다.
      요청 임베딩은 7.5의 Redis 캐시를 다시 쓰므로 같은 글이면 임베딩 호출이 늘지 않는다.
    - 저장 전 DROP이라 DB에 남기지 않고, GENERATED·DISCARDED 결과를 Redis에 판매자·입력 해시별로 하루 캐시한다.
      Redis 장애는 캐시가 없는 것과 같게 본다(다시 만들 수 있는 값이다).
    - 하루 한도는 수요 분석 설명(3.1)과 함께 센다. 트랜잭션을 열지 않는다(재고 추천·비슷한 DROP 실적은 각자 짧게 끝난다).
 */
@Slf4j
@Service
public class StockExplanationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String KEY_PREFIX = "demand:explanation:form:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);

    private final StockRecommendationService stockRecommendationService;
    private final SimilarSalesService similarSalesService;
    private final DemandExplanationGenerator generator;
    private final DailyRequestLimitRepository limitRepository;
    private final DemandExplanationProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public StockExplanationService(
            StockRecommendationService stockRecommendationService,
            SimilarSalesService similarSalesService,
            DemandExplanationGenerator generator,
            DailyRequestLimitRepository limitRepository,
            DemandExplanationProperties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this(stockRecommendationService, similarSalesService, generator, limitRepository, properties, redisTemplate,
                objectMapper, Clock.systemUTC());
    }

    StockExplanationService(
            StockRecommendationService stockRecommendationService,
            SimilarSalesService similarSalesService,
            DemandExplanationGenerator generator,
            DailyRequestLimitRepository limitRepository,
            DemandExplanationProperties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.stockRecommendationService = stockRecommendationService;
        this.similarSalesService = similarSalesService;
        this.generator = generator;
        this.limitRepository = limitRepository;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DemandExplanationResponse explain(long sellerId, StockExplanationRequest request) {
        // 카테고리 검증은 재고 추천과 같다(5.5)
        StockRecommendationResponse recommendation = stockRecommendationService.recommend(
                sellerId, request.toStockRecommendationRequest());
        if (!generator.available()) {
            return DemandExplanationResponse.of(ExplanationStatus.DISABLED);
        }
        SimilarSalesResponse similarSales = similarSalesService.forForm(sellerId, request.toSimilarSalesRequest());
        String input = StockExplanationPrompt.input(recommendation, similarSales, request.options(), objectMapper);
        String key = KEY_PREFIX + sellerId + ":" + DemandExplanationGenerator.inputHash(StockExplanationPrompt.VERSION, input);
        Optional<DemandExplanationResponse> cached = cached(key);
        if (cached.isPresent()) {
            generator.countReused(DemandExplanationGenerator.STAGE_FORM);
            return cached.get();
        }
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate();
        long requests = limitRepository.increment(DemandExplanationService.LIMIT_SCOPE, sellerId, today);
        if (requests > properties.dailyLimit()) {
            throw new BusinessException(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED);
        }
        Outcome outcome = generator.generate(StockExplanationPrompt.SYSTEM, input, DemandExplanationGenerator.STAGE_FORM);
        if (outcome.status() == ExplanationStatus.FAILED) {
            // 다음 요청에서 다시 시도하도록 남기지 않는다(3.4)
            return DemandExplanationResponse.of(ExplanationStatus.FAILED);
        }
        DemandExplanationResponse response = new DemandExplanationResponse(
                outcome.status(), outcome.explanation(), OffsetDateTime.now(clock));
        cache(key, response);
        return response;
    }

    private Optional<DemandExplanationResponse> cached(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, DemandExplanationResponse.class));
        } catch (RuntimeException e) {
            log.warn("추천 근거 설명 캐시 조회 실패: cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void cache(String key, DemandExplanationResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("추천 근거 설명 캐시 저장 실패: cause={}", e.getClass().getSimpleName());
        }
    }
}
