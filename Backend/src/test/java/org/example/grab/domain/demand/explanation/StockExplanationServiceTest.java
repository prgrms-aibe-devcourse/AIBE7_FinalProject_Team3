package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.dto.SimilarSalesRequest;
import org.example.grab.domain.demand.dto.StockExplanationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.example.grab.domain.demand.service.SimilarSalesService;
import org.example.grab.domain.demand.service.StockRecommendationService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StockExplanationServiceTest {

    private static final long SELLER_ID = 3L;
    // 2026-10-28 23:30 KST → 한도는 KST 날짜로 센다
    private static final Instant NOW = Instant.parse("2026-10-28T14:30:00Z");
    private static final StockExplanationRequest REQUEST = new StockExplanationRequest(8L, "키링", "아크릴", null,
            StockExplanationPromptTest.OPTIONS, new BigDecimal("0.1"));

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private StockRecommendationService stockRecommendationService;
    private SimilarSalesService similarSalesService;
    private DemandExplanationClient client;
    private DailyRequestLimitRepository limitRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meterRegistry;
    private StockExplanationService service;
    private String input;
    private String key;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stockRecommendationService = mock(StockRecommendationService.class);
        similarSalesService = mock(SimilarSalesService.class);
        client = mock(DemandExplanationClient.class);
        limitRepository = mock(DailyRequestLimitRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        meterRegistry = new SimpleMeterRegistry();
        given(redisTemplate.opsForValue()).willReturn(values);
        given(stockRecommendationService.recommend(SELLER_ID, REQUEST.toStockRecommendationRequest()))
                .willReturn(StockExplanationPromptTest.recommendation());
        given(similarSalesService.forForm(SELLER_ID, REQUEST.toSimilarSalesRequest()))
                .willReturn(StockExplanationPromptTest.similarSales());
        given(client.available()).willReturn(true);
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(1L);
        input = StockExplanationPrompt.input(StockExplanationPromptTest.recommendation(),
                StockExplanationPromptTest.similarSales(), StockExplanationPromptTest.OPTIONS, objectMapper);
        key = "demand:explanation:form:" + SELLER_ID + ":"
                + DemandExplanationGenerator.inputHash(StockExplanationPrompt.VERSION, input);
        DemandExplanationProperties properties = new DemandExplanationProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(5), 1024, 0, 30);
        DemandExplanationGenerator generator = new DemandExplanationGenerator(client, properties, objectMapper,
                Runnable::run, meterRegistry);
        service = new StockExplanationService(stockRecommendationService, similarSalesService, generator,
                limitRepository, properties, redisTemplate, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("재고 추천·비슷한 DROP 실적으로 설명을 만들고, 검증을 통과하면 Redis에 하루 캐시해 GENERATED로 응답한다")
    void explain_generatesAndCaches() {
        // given: 숫자(44·67·12)는 입력의 준비 권장 수량·내 비슷한 DROP 매진 비율·매진 시간과 같다
        given(client.generate(StockExplanationPrompt.SYSTEM, input)).willReturn(new Generation(
                "{\"summary\":\"시장 평균 판매 실적으로 준비 권장 수량은 44개입니다.\","
                        + "\"reasons\":[\"내 비슷한 DROP의 67%가 매진됐고, 매진까지 보통 12분 걸렸습니다.\"]}",
                "gemini-2.5-flash", 700));

        // when
        DemandExplanationResponse response = service.explain(SELLER_ID, REQUEST);

        // then: 한도는 수요 분석 설명과 같은 범위로 센다
        assertThat(response.status()).isEqualTo(ExplanationStatus.GENERATED);
        assertThat(response.explanation().reasons()).hasSize(1);
        verify(limitRepository).increment(DemandExplanationService.LIMIT_SCOPE, SELLER_ID, LocalDate.of(2026, 10, 28));
        verify(values).set(eq(key), anyString(), eq(Duration.ofDays(1)));
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "generated", "stage", "form").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 입력의 설명이 캐시돼 있으면 LLM을 부르지 않고 한도도 세지 않는다")
    void explain_reusesCached() {
        DemandExplanationResponse cached = new DemandExplanationResponse(ExplanationStatus.GENERATED,
                new DemandExplanation("준비 권장 수량은 44개입니다.", List.of(), List.of(), List.of(), List.of()),
                NOW.atOffset(ZoneOffset.UTC));
        given(values.get(key)).willReturn(objectMapper.writeValueAsString(cached));

        DemandExplanationResponse response = service.explain(SELLER_ID, REQUEST);

        assertThat(response.explanation().summary()).isEqualTo("준비 권장 수량은 44개입니다.");
        verify(client, never()).generate(anyString(), anyString());
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "reused", "stage", "form").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("입력에 없는 숫자를 쓴 설명은 DISCARDED로 캐시하고, 호출 실패는 캐시하지 않는다")
    void explain_discardsAndSkipsCachingFailures() {
        given(client.generate(StockExplanationPrompt.SYSTEM, input))
                .willReturn(new Generation("{\"summary\":\"준비 권장 수량은 70개입니다.\"}", "gemini-2.5-flash", 500))
                .willThrow(new IllegalStateException("boom"));

        assertThat(service.explain(SELLER_ID, REQUEST).status()).isEqualTo(ExplanationStatus.DISCARDED);
        verify(values).set(eq(key), anyString(), eq(Duration.ofDays(1)));

        assertThat(service.explain(SELLER_ID, REQUEST).status()).isEqualTo(ExplanationStatus.FAILED);
        verify(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 비슷한 DROP 실적·LLM·한도 없이 DISABLED로 응답한다")
    void explain_disabled() {
        given(client.available()).willReturn(false);

        assertThat(service.explain(SELLER_ID, REQUEST).status()).isEqualTo(ExplanationStatus.DISABLED);
        verify(similarSalesService, never()).forForm(anyLong(), any(SimilarSalesRequest.class));
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("하루 한도를 넘으면 LLM을 부르지 않고 DEMAND_EXPLANATION_LIMIT_EXCEEDED로 거부한다")
    void explain_rejectsOverLimit() {
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(31L);

        assertThatThrownBy(() -> service.explain(SELLER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED);
        verify(client, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 장애는 캐시가 없는 것처럼 보고 설명을 만든다")
    void explain_toleratesRedisFailure() {
        given(values.get(key)).willThrow(new RedisConnectionFailureException("down"));
        given(client.generate(StockExplanationPrompt.SYSTEM, input)).willReturn(new Generation(
                "{\"summary\":\"준비 권장 수량은 44개입니다.\"}", "gemini-2.5-flash", 300));

        assertThat(service.explain(SELLER_ID, REQUEST).status()).isEqualTo(ExplanationStatus.GENERATED);
    }

    @Test
    @DisplayName("재고 추천 요청은 폼 입력의 카테고리·옵션·안전 재고율을, 비슷한 DROP 실적 요청은 옵션 단가를 쓴다")
    void request_splitsIntoUnderlyingRequests() {
        assertThat(REQUEST.toStockRecommendationRequest()).isEqualTo(new StockRecommendationRequest(8L,
                StockExplanationPromptTest.OPTIONS, new BigDecimal("0.1")));
        assertThat(REQUEST.toSimilarSalesRequest()).isEqualTo(new SimilarSalesRequest(8L, "키링", "아크릴", null,
                List.of(12000L, 12000L)));
    }
}
