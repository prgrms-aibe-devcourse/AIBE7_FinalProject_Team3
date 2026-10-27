package org.example.grab.domain.demand.option;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.OptionAllocationRequest;
import org.example.grab.domain.demand.dto.OptionAllocationRequest.SkuInput;
import org.example.grab.domain.demand.dto.OptionAllocationResponse;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.Allocation;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.MatchStatus;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.OptionAllocation;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OptionRecommendation;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.Recommendation;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.SampleSelections;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.option.OptionMatchClient.Generation;
import org.example.grab.domain.demand.repository.OptionMatchRepository;
import org.example.grab.domain.demand.repository.OptionMatchRepository.OptionSales;
import org.example.grab.domain.demand.service.StockRecommendationService;
import org.example.grab.domain.demand.service.StockRecommendationService.Calculation;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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

class OptionAllocationServiceTest {

    private static final long SELLER_ID = 3L;
    // 2026-10-27 23:30 KST → 한도는 KST 날짜로 센다
    private static final Instant NOW = Instant.parse("2026-10-27T14:30:00Z");
    private static final List<Long> DROP_IDS = List.of(11L, 12L, 13L);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private StockRecommendationService stockRecommendationService;
    private OptionMatchRepository repository;
    private OptionMatchClient client;
    private DailyRequestLimitRepository limitRepository;
    private OptionAllocationService service;

    @BeforeEach
    void setUp() {
        stockRecommendationService = mock(StockRecommendationService.class);
        repository = mock(OptionMatchRepository.class);
        client = mock(OptionMatchClient.class);
        limitRepository = mock(DailyRequestLimitRepository.class);
        given(client.available()).willReturn(true);
        given(repository.findMatches(anyString())).willReturn(Optional.empty());
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(1L);
        given(stockRecommendationService.calculateWithSamples(eq(SELLER_ID), any()))
                .willReturn(calculation(ConversionBasis.OWN, 40));
        service = new OptionAllocationService(stockRecommendationService, repository, client, limitRepository,
                new OptionMatchProperties(Duration.ofSeconds(10), 30, 100), objectMapper, Runnable::run,
                new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("모든 값이 글자로 맞으면 LLM 없이 비슷한 상품 판매 비율로 나눈다")
    void allocate_textOnly() {
        // given: 내 DROP 3개에서 블랙 30·화이트 10
        given(repository.findOptionSales(DROP_IDS)).willReturn(sales("블랙", "화이트"));

        // when
        OptionAllocationResponse response = service.allocate(SELLER_ID, request("블랙", "화이트"));

        // then
        assertThat(response.matchStatus()).isEqualTo(MatchStatus.TEXT_ONLY);
        assertThat(response.groups().get(0).allocation()).isEqualTo(Allocation.SALES);
        assertThat(response.groups().get(0).values()).extracting(value -> value.sharePercent())
                .containsExactly(75, 25);
        assertThat(response.options()).extracting(OptionAllocation::recommendedQuantity).containsExactly(30, 10);
        verify(client, never()).generate(anyString(), anyString());
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("글자로 맞지 않은 값은 LLM이 고른 후보 번호로 맞추고, 결과를 저장하며 한도를 센다")
    void allocate_matchesWithLlm() {
        // given: 판매자는 "블랙", 후보는 "검정"(0번, 판매량 30)·"화이트"(1번, 10)
        given(repository.findOptionSales(DROP_IDS)).willReturn(sales("검정", "화이트"));
        given(client.generate(eq(OptionMatchPrompt.SYSTEM), anyString())).willReturn(new Generation(
                "{\"matches\":[{\"group\":0,\"value\":0,\"candidates\":[0]},{\"group\":0,\"value\":1,\"candidates\":[1]}]}",
                "gemini-2.5-flash-lite"));

        // when
        OptionAllocationResponse response = service.allocate(SELLER_ID, request("블랙", "화이트"));

        // then
        assertThat(response.matchStatus()).isEqualTo(MatchStatus.GENERATED);
        assertThat(response.options()).extracting(OptionAllocation::recommendedQuantity).containsExactly(30, 10);
        verify(limitRepository).increment(OptionAllocationService.LIMIT_SCOPE, SELLER_ID, LocalDate.of(2026, 10, 27));
        verify(repository).saveMatches(anyString(), anyString(), eq("gemini-2.5-flash-lite"),
                eq(OptionMatchPrompt.VERSION), any());
    }

    @Test
    @DisplayName("같은 입력의 매칭이 저장돼 있으면 LLM을 부르지 않고 한도도 세지 않는다")
    void allocate_reusesStoredMatches() {
        given(repository.findOptionSales(DROP_IDS)).willReturn(sales("검정", "화이트"));
        given(repository.findMatches(anyString())).willReturn(Optional.of(
                "{\"matches\":[{\"group\":0,\"value\":0,\"candidates\":[0]}]}"));

        OptionAllocationResponse response = service.allocate(SELLER_ID, request("블랙", "화이트"));

        assertThat(response.matchStatus()).isEqualTo(MatchStatus.REUSED);
        assertThat(response.groups().get(0).allocation()).isEqualTo(Allocation.SALES);
        verify(client, never()).generate(anyString(), anyString());
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("LLM 호출이 실패하거나 형식이 틀리면 글자 매칭 결과로 계산하고, 맞지 않은 값이 있는 그룹은 균등이다")
    void allocate_fallsBackToTextMatches() {
        given(repository.findOptionSales(DROP_IDS)).willReturn(sales("검정", "화이트"));
        given(client.generate(anyString(), anyString())).willThrow(new IllegalStateException("network"));

        OptionAllocationResponse failed = service.allocate(SELLER_ID, request("블랙", "화이트"));

        assertThat(failed.matchStatus()).isEqualTo(MatchStatus.FAILED);
        assertThat(failed.groups().get(0).allocation()).isEqualTo(Allocation.EVEN);
        assertThat(failed.options()).extracting(OptionAllocation::recommendedQuantity).containsExactly(20, 20);
        verify(repository, never()).saveMatches(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 DISABLED로 글자 매칭만 쓰고, 한도를 넘으면 LLM을 부르지 않고 거부한다")
    void allocate_disabledOrLimited() {
        given(repository.findOptionSales(DROP_IDS)).willReturn(sales("검정", "화이트"));
        given(client.available()).willReturn(false);
        assertThat(service.allocate(SELLER_ID, request("블랙", "화이트")).matchStatus())
                .isEqualTo(MatchStatus.DISABLED);

        given(client.available()).willReturn(true);
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(31L);
        assertThatThrownBy(() -> service.allocate(SELLER_ID, request("블랙", "화이트")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.STOCK_OPTION_MATCH_LIMIT_EXCEEDED);
        verify(client, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("추천 기준이 없으면 매칭하지 않고 수량 없이 균등 그룹으로 응답한다")
    void allocate_withoutBasis() {
        given(stockRecommendationService.calculateWithSamples(eq(SELLER_ID), any()))
                .willReturn(calculation(null, null));

        OptionAllocationResponse response = service.allocate(SELLER_ID, request("블랙", "화이트"));

        assertThat(response.matchStatus()).isNull();
        assertThat(response.recommended()).isNull();
        assertThat(response.options()).extracting(OptionAllocation::recommendedQuantity).containsOnlyNulls();
        verify(repository, never()).findOptionSales(any());
    }

    @Test
    @DisplayName("SKU 값이 옵션 구성 순서·개수와 맞지 않으면 VALIDATION_FAILED(options)로 거부한다")
    void allocate_rejectsMismatchedValues() {
        OptionAllocationRequest request = new OptionAllocationRequest(1L,
                List.of(new SkuInput(20000L, null, List.of("레드"))), null, "티셔츠", "코튼",
                List.of(new OptionGroupInput("색상", List.of("블랙"))));

        assertThatThrownBy(() -> service.allocate(SELLER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        verify(stockRecommendationService, never()).calculateWithSamples(anyLong(), any());
    }

    private static OptionAllocationRequest request(String... colors) {
        return new OptionAllocationRequest(1L,
                java.util.Arrays.stream(colors).map(color -> new SkuInput(20000L, null, List.of(color))).toList(),
                null, "티셔츠", "코튼 티셔츠", List.of(new OptionGroupInput("색상", List.of(colors))));
    }

    // 표본 DROP 3개(판매자 1명)가 첫 값 10개·둘째 값 약 3개씩 팔았다: 합 30·10
    private static List<OptionSales> sales(String first, String second) {
        return List.of(
                new OptionSales(11, 3, "색상", first, 10), new OptionSales(11, 3, "색상", second, 4),
                new OptionSales(12, 3, "색상", first, 10), new OptionSales(12, 3, "색상", second, 3),
                new OptionSales(13, 3, "색상", first, 10), new OptionSales(13, 3, "색상", second, 3));
    }

    private static Calculation calculation(ConversionBasis basis, Integer recommended) {
        List<SalesSample> samples = DROP_IDS.stream()
                .map(id -> new SalesSample(id, SELLER_ID, 20000, 20, 10, false)).toList();
        StockRecommendationResponse response = new StockRecommendationResponse(null, "s1", 20000, null, null, basis,
                new SampleSelections(SampleSelection.SIMILAR, SampleSelection.CATEGORY),
                new Recommendation(null, null, null, recommended, BigDecimal.ZERO,
                        List.of(new OptionRecommendation(0, recommended == null ? null : recommended / 2),
                                new OptionRecommendation(1, recommended == null ? null : recommended / 2))),
                null, List.of());
        return new Calculation(response, basis == null ? List.of() : samples, List.of());
    }
}
