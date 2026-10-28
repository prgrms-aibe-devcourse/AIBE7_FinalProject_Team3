package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.config.DemandSuggestionProperties;
import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.DailyWish;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.InitialRecommendation;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandDataGap;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.MarketSample;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DemandAnalysisServiceTest {

    private static final long SELLER_ID = 3L;
    private static final long DROP_ID = 100L;
    private static final long CATEGORY_ID = 7L;
    // KST 2026-10-22 14:00
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T05:00:00Z");

    private DemandAnalysisRepository repository;
    private DemandAnalysisService service;

    @BeforeEach
    void setUp() {
        repository = mock(DemandAnalysisRepository.class);
        service = new DemandAnalysisService(repository, new DemandSuggestionProperties(30, 48),
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        given(repository.findMarketConversionSample(anyLong(), anyLong(), any(), anyInt()))
                .willReturn(MarketSample.empty());
    }

    @Test
    @DisplayName("본인 WISH DROP의 수요 데이터·내 실적 전환율·판매 계획을 함께 반환한다")
    void analyze_assemblesResponse() {
        // given
        givenTarget("WISH", NOW.plusHours(40).plusMinutes(59));
        given(repository.countActiveWishes(DROP_ID)).willReturn(100L);
        // 14일 전 0시(KST)부터 센다
        given(repository.countDailyActiveWishes(DROP_ID, OffsetDateTime.parse("2026-10-09T00:00:00+09:00")))
                .willReturn(Map.of(LocalDate.parse("2026-10-22"), 12L, LocalDate.parse("2026-10-21"), 35L));
        given(repository.findOwnConversionSample(SELLER_ID, CATEGORY_ID, DROP_ID, 20))
                .willReturn(new ConversionSample(3, 85, 34, 34));
        given(repository.findActiveOptions(DROP_ID)).willReturn(List.of(new DemandOption(1, "기본", 40, 0)));

        // when
        DemandAnalysisResponse response = service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults());

        // then
        assertThat(response.calculatedAt()).isEqualTo(NOW);
        assertThat(response.calculationVersion()).isEqualTo("v1");
        assertThat(response.demand().activeWishCount()).isEqualTo(100);
        assertThat(response.demand().hoursUntilSaleStart()).isEqualTo(40);
        assertThat(response.demand().trendApproximate()).isTrue();
        assertThat(response.demand().dailyWishes()).hasSize(14);
        assertThat(response.demand().dailyWishes().get(0)).isEqualTo(new DailyWish(LocalDate.parse("2026-10-09"), 0));
        assertThat(response.demand().dailyWishes()).endsWith(
                new DailyWish(LocalDate.parse("2026-10-21"), 35),
                new DailyWish(LocalDate.parse("2026-10-22"), 12));
        assertThat(response.suggestion().ready()).isTrue();
        assertThat(response.conversion().selection()).isEqualTo(SampleSelection.CATEGORY);
        assertThat(response.conversion().own().dropCount()).isEqualTo(3);
        assertThat(response.conversion().market().confidence()).isEqualTo(ConversionConfidence.NONE);
        assertThat(response.conversion().market().dropCountBand()).isEqualTo("NONE");
        assertThat(response.plan().expected()).isEqualTo(40);
        assertThat(response.dataGaps()).containsExactly(DemandDataGap.SMALL_CONVERSION_SAMPLE);
        assertThat(response.notes()).containsExactly(DemandAnalysisService.WISH_ONLY_NOTE);
    }

    @Test
    @DisplayName("시장 표본은 오늘 0시(KST) 이전 종료분으로 조회하고 보호 규칙을 거친 값만 응답한다")
    void analyze_queriesMarketAsOfStartOfDay() {
        // given: KST 2026-10-22 14:00 → 기준 시점 2026-10-22 00:00 KST
        OffsetDateTime asOf = OffsetDateTime.parse("2026-10-22T00:00:00+09:00");
        givenTarget("WISH", NOW.plusDays(1));
        given(repository.countActiveWishes(DROP_ID)).willReturn(420L);
        given(repository.findOwnConversionSample(SELLER_ID, CATEGORY_ID, DROP_ID, 20)).willReturn(ConversionSample.empty());
        given(repository.findMarketConversionSample(SELLER_ID, CATEGORY_ID, asOf, 20))
                .willReturn(new MarketSample(12, 6, 1745, 523, 600));

        // when
        DemandAnalysisResponse response = service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults());

        // then
        assertThat(response.conversion().market().asOf()).isEqualTo(asOf);
        assertThat(response.conversion().market().rate()).isEqualByComparingTo("0.30");
        assertThat(response.conversion().market().dropCountBand()).isEqualTo("10_TO_19");
        assertThat(response.conversion().market().wishCount()).isEqualTo(1740L);
        assertThat(response.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.MARKET);
        // base = 420 × 0.30 × 1.1(600 / 523 반올림)
        assertThat(response.plan().expected()).isEqualTo(139);
    }

    @Test
    @DisplayName("활성 WISH가 기준 미만이고 판매 시작이 멀면 제안 표시 여부가 거짓이다")
    void analyze_notReadyBeforeThresholds() {
        // given
        givenTarget("WISH", NOW.plusHours(49));
        given(repository.countActiveWishes(DROP_ID)).willReturn(29L);
        given(repository.findOwnConversionSample(SELLER_ID, CATEGORY_ID, DROP_ID, 20)).willReturn(ConversionSample.empty());

        // when
        DemandAnalysisResponse response = service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults());

        // then
        assertThat(response.suggestion().ready()).isFalse();
    }

    @Test
    @DisplayName("없는 DROP은 DROP_NOT_FOUND로 거부한다")
    void analyze_rejectsMissingDrop() {
        given(repository.findTargetDrop(DROP_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매자의 DROP은 집계하지 않고 DROP_ACCESS_DENIED로 거부한다")
    void analyze_rejectsOtherSellersDrop() {
        // given
        given(repository.findTargetDrop(DROP_ID)).willReturn(Optional.of(
                new TargetDrop(DROP_ID, SELLER_ID + 1, "WISH", CATEGORY_ID, NOW.plusDays(1))));

        // when & then
        assertThatThrownBy(() -> service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
        verify(repository, never()).countActiveWishes(anyLong());
    }

    @Test
    @DisplayName("WISH 상태가 아닌 DROP은 DEMAND_ANALYSIS_UNAVAILABLE로 거부한다")
    void analyze_rejectsNonWishDrop() {
        givenTarget("GRAB", NOW.plusDays(1));

        assertThatThrownBy(() -> service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_ANALYSIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("판매 시작 시각이 지났으면 상태 전환 전이라도 DEMAND_ANALYSIS_UNAVAILABLE로 거부한다")
    void analyze_rejectsAfterSaleStart() {
        givenTarget("WISH", NOW);

        assertThatThrownBy(() -> service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_ANALYSIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("공개 시점 재고 추천 기록이 있으면 비교용으로 함께 반환하고, 없으면 null이다")
    void analyze_includesInitialRecommendation() {
        // given
        givenTarget("WISH", NOW.plusDays(1));
        given(repository.findOwnConversionSample(SELLER_ID, CATEGORY_ID, DROP_ID, 20))
                .willReturn(ConversionSample.empty());
        InitialRecommendation initial = new InitialRecommendation(
                NOW.minusDays(3), ConversionBasis.MARKET, 25, 40, 60, 44, 120);
        given(repository.findInitialRecommendation(DROP_ID)).willReturn(Optional.of(initial));

        // when
        DemandAnalysisResponse response = service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults());

        // then
        assertThat(response.initialRecommendation()).isEqualTo(initial);
        given(repository.findInitialRecommendation(DROP_ID)).willReturn(Optional.empty());
        assertThat(service.analyze(SELLER_ID, DROP_ID, DemandCalculateRequest.defaults()).initialRecommendation())
                .isNull();
    }

    @Test
    @DisplayName("판매 시작 시점 기록용 계산은 상태·시각 조건 없이 기본 가정으로 하고, 남은 시간은 0이다")
    void analyzeAtSaleStart_ignoresAvailabilityAndUsesDefaults() {
        // given: 이미 GRAB으로 전환되고 판매 시작 시각이 지난 DROP
        TargetDrop started = new TargetDrop(DROP_ID, SELLER_ID, "GRAB", CATEGORY_ID, NOW.minusMinutes(5));
        given(repository.countActiveWishes(DROP_ID)).willReturn(100L);
        given(repository.findOwnConversionSample(SELLER_ID, CATEGORY_ID, DROP_ID, 20))
                .willReturn(new ConversionSample(3, 85, 34, 34));
        given(repository.findActiveOptions(DROP_ID)).willReturn(List.of(new DemandOption(1, "기본", 40, 0)));

        // when
        DemandAnalysisResponse response = service.analyzeAtSaleStart(started);

        // then
        assertThat(response.demand().hoursUntilSaleStart()).isZero();
        assertThat(response.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.OWN);
        assertThat(response.assumptions().safetyStockRate().source()).isEqualTo(AssumptionSource.DEFAULT);
        assertThat(response.plan().expected()).isEqualTo(40);
    }

    private void givenTarget(String status, OffsetDateTime saleStartsAt) {
        given(repository.findTargetDrop(DROP_ID)).willReturn(Optional.of(
                new TargetDrop(DROP_ID, SELLER_ID, status, CATEGORY_ID, saleStartsAt)));
    }
}
