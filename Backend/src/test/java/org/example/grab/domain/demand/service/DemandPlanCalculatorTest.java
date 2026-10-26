package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.MarketConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandDataGap;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.MarketSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemandPlanCalculatorTest {

    // 내 실적: DROP 3개, WISH 85명 중 34명 구매(1인 1개) → 전환율 0.40, 신뢰도 LOW
    private static final ConversionSample OWN_LOW = new ConversionSample(3, 85, 34, 34);
    // 내 실적: DROP 10개, WISH 200명 중 50명이 75개 구매 → 전환율 0.25, 구매자당 1.5개, 신뢰도 HIGH
    private static final ConversionSample OWN_HIGH = new ConversionSample(10, 200, 50, 75);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-22T00:00:00+09:00");
    // 시장 평균: 전환율 0.30, 윌슨 구간 0.27~0.33(WISH 약 1,740명 기준), 신뢰도 HIGH
    private static final MarketConversion MARKET_HIGH = new MarketConversion(
            new BigDecimal("0.30"), new BigDecimal("0.27"), new BigDecimal("0.33"), new BigDecimal("1.0"),
            ConversionConfidence.HIGH, "10_TO_19", 1740L, AS_OF);
    private static final MarketConversion NO_MARKET = MarketConversion.unavailable(AS_OF);
    private static final List<DemandOption> THREE_OPTIONS = List.of(
            new DemandOption(1001, "블랙 / M", 40, 0),
            new DemandOption(1002, "블랙 / L", 40, 1),
            new DemandOption(1003, "화이트 / M", 40, 2));

    @Test
    @DisplayName("DEMAND.md 2.1 예시: 신뢰도가 높은 시장 평균을 기본 기준으로 써서 범위·추천·옵션 배분을 계산한다")
    void calculate_matchesSpecExample() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                420, OWN_LOW, MARKET_HIGH, THREE_OPTIONS, DemandCalculateRequest.defaults());

        // then
        assertThat(result.own().rate()).isEqualByComparingTo("0.40");
        assertThat(result.own().confidence()).isEqualTo(ConversionConfidence.LOW);
        assertThat(result.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.MARKET);
        assertThat(result.assumptions().safetyStockRate().source()).isEqualTo(AssumptionSource.DEFAULT);
        // low = floor(420 × 0.27 × 1.0), high = ceil(420 × 0.33 × 1.0)
        assertThat(result.plan().low()).isEqualTo(113);
        assertThat(result.plan().expected()).isEqualTo(126);
        assertThat(result.plan().high()).isEqualTo(139);
        assertThat(result.plan().recommended()).isEqualTo(139);
        assertThat(result.plan().currentTotalQuantity()).isEqualTo(120);
        assertThat(result.plan().differenceFromCurrent()).isEqualTo(19);
        assertThat(result.plan().options()).extracting(OptionPlan::recommendedQuantity).containsExactly(47, 46, 46);
        assertThat(result.dataGaps()).isEmpty();
    }

    @Test
    @DisplayName("시장 평균이 없으면 내 실적을 기본 기준으로 쓰고 범위는 내 실적의 윌슨 구간으로 계산한다")
    void calculate_usesOwnWhenMarketUnavailable() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, OWN_LOW, NO_MARKET, THREE_OPTIONS, DemandCalculateRequest.defaults());

        // then: base = 100 × 0.40 × 1.0 = 40, 전환율 구간 0.30~0.51
        assertThat(result.own().rateLow()).isEqualByComparingTo("0.30");
        assertThat(result.own().rateHigh()).isEqualByComparingTo("0.51");
        assertThat(result.assumptions().conversionRate()).isEqualTo(new Assumption(new BigDecimal("0.40"), AssumptionSource.OWN));
        assertThat(result.plan().low()).isEqualTo(30);
        assertThat(result.plan().expected()).isEqualTo(40);
        assertThat(result.plan().high()).isEqualTo(51);
        assertThat(result.plan().recommended()).isEqualTo(44);
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.SMALL_CONVERSION_SAMPLE);
    }

    @Test
    @DisplayName("두 기준의 신뢰도가 같으면 내 실적을 기본 기준으로 쓴다")
    void calculate_prefersOwnOnTie() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, OWN_HIGH, MARKET_HIGH, THREE_OPTIONS, DemandCalculateRequest.defaults());

        // then: base = 100 × 0.25 × 1.5 = 37.5, 전환율 구간 0.19~0.32
        assertThat(result.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.OWN);
        assertThat(result.assumptions().avgQuantityPerBuyer().value()).isEqualByComparingTo("1.5");
        assertThat(result.plan().expected()).isEqualTo(38);
        assertThat(result.plan().low()).isEqualTo(28);
        assertThat(result.plan().high()).isEqualTo(48);
        assertThat(result.plan().recommended()).isEqualTo(42);
    }

    @Test
    @DisplayName("판매자가 기준을 지정하면 기본 기준 대신 그 기준을 쓴다")
    void calculate_usesRequestedBasis() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                420, OWN_LOW, MARKET_HIGH, THREE_OPTIONS,
                new DemandCalculateRequest(ConversionBasis.OWN, null, null));

        // then: base = 420 × 0.40 × 1.0 = 168
        assertThat(result.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.OWN);
        assertThat(result.plan().expected()).isEqualTo(168);
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.SMALL_CONVERSION_SAMPLE);
    }

    @Test
    @DisplayName("신뢰도 NONE인 기준을 지정하면 범위·추천 수량 없이 표본 없음을 반환한다")
    void calculate_rejectsRequestedBasisWithoutSample() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                420, OWN_LOW, NO_MARKET, THREE_OPTIONS,
                new DemandCalculateRequest(ConversionBasis.MARKET, null, null));

        // then
        assertThat(result.assumptions().conversionRate()).isNull();
        assertThat(result.assumptions().avgQuantityPerBuyer()).isNull();
        assertThat(result.plan().recommended()).isNull();
        assertThat(result.plan().differenceFromCurrent()).isNull();
        assertThat(result.plan().options()).extracting(OptionPlan::recommendedQuantity).containsOnlyNulls();
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.NO_CONVERSION_SAMPLE);
    }

    @Test
    @DisplayName("두 기준 모두 표본이 부족하면 추천 수량을 억지로 만들지 않는다")
    void calculate_returnsNoPlanWithoutAnySample() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                420, ConversionSample.empty(), NO_MARKET, THREE_OPTIONS,
                DemandCalculateRequest.defaults());

        // then
        assertThat(result.plan().low()).isNull();
        assertThat(result.plan().currentTotalQuantity()).isEqualTo(120);
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.NO_CONVERSION_SAMPLE);
    }

    @Test
    @DisplayName("판매자 입력 전환율은 범위 폭 0.3을 쓰고 구매자당 수량은 기본 기준 값을 쓴다")
    void calculate_usesSellerInputRate() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, OWN_HIGH, NO_MARKET, THREE_OPTIONS,
                new DemandCalculateRequest(null, new BigDecimal("0.20"), new BigDecimal("0.05")));

        // then: base = 100 × 0.20 × 1.5 = 30
        assertThat(result.assumptions().conversionRate().source()).isEqualTo(AssumptionSource.SELLER_INPUT);
        assertThat(result.assumptions().avgQuantityPerBuyer().source()).isEqualTo(AssumptionSource.OWN);
        assertThat(result.assumptions().safetyStockRate().source()).isEqualTo(AssumptionSource.SELLER_INPUT);
        assertThat(result.plan().low()).isEqualTo(21);
        assertThat(result.plan().expected()).isEqualTo(30);
        assertThat(result.plan().high()).isEqualTo(39);
        assertThat(result.plan().recommended()).isEqualTo(32);
        assertThat(result.dataGaps()).isEmpty();
    }

    @Test
    @DisplayName("판매자 입력 전환율의 범위 상단은 전환율 1을 넘지 않는다")
    void calculate_capsSellerInputRangeAtOne() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, ConversionSample.empty(), NO_MARKET, THREE_OPTIONS,
                new DemandCalculateRequest(null, new BigDecimal("0.90"), null));

        // then: 0.90 × 1.3 = 1.17이지만 상단은 100 × 1.0 × 1.0
        assertThat(result.plan().low()).isEqualTo(63);
        assertThat(result.plan().high()).isEqualTo(100);
    }

    @Test
    @DisplayName("기준이 하나도 없을 때 판매자 입력 전환율은 구매자당 수량 기본값 1을 쓴다")
    void calculate_sellerInputWithoutAnyBasisUsesDefaultQuantity() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                50, ConversionSample.empty(), NO_MARKET, THREE_OPTIONS,
                new DemandCalculateRequest(null, new BigDecimal("0.50"), null));

        // then
        assertThat(result.assumptions().avgQuantityPerBuyer())
                .isEqualTo(new Assumption(new BigDecimal("1.0"), AssumptionSource.DEFAULT));
        assertThat(result.plan().expected()).isEqualTo(25);
        assertThat(result.dataGaps()).isEmpty();
    }

    @Test
    @DisplayName("활성 WISH가 없으면 전환율이 있어도 수량을 계산하지 않는다")
    void calculate_returnsNoPlanWithoutActiveWish() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                0, OWN_HIGH, MARKET_HIGH, THREE_OPTIONS, DemandCalculateRequest.defaults());

        // then
        assertThat(result.assumptions().conversionRate()).isNotNull();
        assertThat(result.plan().recommended()).isNull();
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.NO_ACTIVE_WISH);
    }

    @Test
    @DisplayName("구매자가 없는 표본의 구매자당 수량은 1, 전환율은 0으로 계산한다")
    void calculate_handlesSampleWithoutBuyer() {
        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, new ConversionSample(3, 30, 0, 0), NO_MARKET, THREE_OPTIONS,
                DemandCalculateRequest.defaults());

        // then: 전환율이 0이어도 윌슨 구간 상단은 0보다 커서 범위 상단이 남는다
        assertThat(result.own().rate()).isEqualByComparingTo("0");
        assertThat(result.own().rateLow()).isEqualByComparingTo("0");
        assertThat(result.own().rateHigh()).isEqualByComparingTo("0.12");
        assertThat(result.own().avgQuantityPerBuyer()).isEqualByComparingTo("1.0");
        assertThat(result.plan().low()).isZero();
        assertThat(result.plan().high()).isEqualTo(12);
        assertThat(result.plan().recommended()).isZero();
        assertThat(result.plan().options()).extracting(OptionPlan::recommendedQuantity).containsExactly(0, 0, 0);
    }

    @ParameterizedTest(name = "DROP {0}개·WISH {1}명 → {2}")
    @CsvSource({
            "10, 100, HIGH",
            "10, 99, LOW",
            "9, 100, LOW",
            "3, 20, LOW",
            "3, 19, NONE",
            "2, 500, NONE",
            "0, 0, NONE"
    })
    @DisplayName("신뢰도는 표본 DROP 수와 WISH 수 경계값으로 판정한다")
    void confidenceOf_boundaries(int dropCount, long wishers, ConversionConfidence expected) {
        assertThat(DemandPlanCalculator.confidenceOf(dropCount, wishers)).isEqualTo(expected);
    }

    // 기대값은 윌슨 점수 구간 공식을 별도로 계산해 소수 둘째 자리에서 하한 내림·상한 올림한 값이다
    @ParameterizedTest(name = "구매 {0}명 / WISH {1}명 → {2} ~ {3}")
    @CsvSource({
            "6, 20, 0.14, 0.52",
            "30, 100, 0.21, 0.40",
            "34, 85, 0.30, 0.51",
            "1000, 2000, 0.47, 0.53",
            "0, 30, 0.00, 0.12",
            "20, 20, 0.83, 1.00"
    })
    @DisplayName("전환율 구간은 표본이 적을수록 넓고 0~1을 벗어나지 않는다")
    void wilsonInterval_matchesFormula(long converted, long wishers, BigDecimal low, BigDecimal high) {
        DemandPlanCalculator.RateInterval interval = DemandPlanCalculator.wilsonInterval(converted, wishers);

        assertThat(interval.low()).isEqualByComparingTo(low);
        assertThat(interval.high()).isEqualByComparingTo(high);
    }

    @Test
    @DisplayName("옵션 배분은 나머지가 큰 옵션부터 남은 수량을 주고 같으면 sort_order 순이다")
    void allocate_largestRemainderWithSortOrderTieBreak() {
        // given: 비율 1:2:2, 추천 7 → 몫 1.4·2.8·2.8
        List<DemandOption> options = List.of(
                new DemandOption(1, "A", 10, 0),
                new DemandOption(2, "B", 20, 2),
                new DemandOption(3, "C", 20, 1));

        // when
        List<OptionPlan> plans = DemandPlanCalculator.allocate(7, options, 50);

        // then: 바닥 1·2·2 = 5, 남은 2개는 나머지 0.8인 C(sort 1), B(sort 2) 순
        assertThat(plans).extracting(OptionPlan::recommendedQuantity).containsExactly(1, 3, 3);
    }

    @Test
    @DisplayName("현재 수량이 모두 0이면 옵션에 똑같이 나눈다")
    void allocate_splitsEvenlyWhenCurrentTotalIsZero() {
        // given
        List<DemandOption> options = List.of(
                new DemandOption(1, "A", 0, 1),
                new DemandOption(2, "B", 0, 0));

        // when
        List<OptionPlan> plans = DemandPlanCalculator.allocate(5, options, 0);

        // then: 2.5씩, 나머지가 같으므로 sort_order가 앞선 B가 1개 더 받는다
        assertThat(plans).extracting(OptionPlan::recommendedQuantity).containsExactly(2, 3);
    }

    @Test
    @DisplayName("시장 평균은 전환율을 정수 %로, 표본 수는 구간으로, WISH 수는 10 단위 내림으로만 내보낸다")
    void toMarketConversion_appliesProtectionRounding() {
        // given: DROP 12개·판매자 6명, WISH 1,745명 중 523명이 600개 구매 → 전환율 0.29971, 구매자당 1.147
        MarketSample sample = new MarketSample(12, 6, 1745, 523, 600);

        // when
        MarketConversion market = DemandPlanCalculator.toMarketConversion(sample, AS_OF);

        // then: 구간은 원래 집계값(0.2787~0.3216)으로 계산한 뒤 정수 %로 내림·올림
        assertThat(market).isEqualTo(new MarketConversion(
                new BigDecimal("0.30"), new BigDecimal("0.27"), new BigDecimal("0.33"), new BigDecimal("1.1"),
                ConversionConfidence.HIGH, "10_TO_19", 1740L, AS_OF));
    }

    @Test
    @DisplayName("시장 표본이 DROP 20개면 표본 수 구간은 20_PLUS다")
    void toMarketConversion_bandsTwentyDrops() {
        MarketConversion market = DemandPlanCalculator.toMarketConversion(
                new MarketSample(20, 9, 400, 100, 100), AS_OF);

        assertThat(market.dropCountBand()).isEqualTo("20_PLUS");
        assertThat(market.wishCount()).isEqualTo(400L);
    }

    @ParameterizedTest(name = "DROP {0}개·판매자 {1}명·WISH {2}명 → 시장 평균 없음")
    @CsvSource({
            "9, 5, 1000",
            "10, 4, 1000",
            "30, 1, 5000",
            "10, 5, 19"
    })
    @DisplayName("최소 집계 단위(판매자 5명·DROP 10개)나 신뢰도 기준에 미달하면 시장 평균 값을 하나도 내보내지 않는다")
    void toMarketConversion_hidesBelowMinimum(int dropCount, int sellerCount, long wishers) {
        // when
        MarketConversion market = DemandPlanCalculator.toMarketConversion(
                new MarketSample(dropCount, sellerCount, wishers, wishers / 3, wishers / 3), AS_OF);

        // then: 표본 수·WISH 수도 숨겨 미달 상태의 개별 실적을 짐작할 단서를 남기지 않는다
        assertThat(market).isEqualTo(NO_MARKET);
        assertThat(market.rate()).isNull();
        assertThat(market.wishCount()).isNull();
        assertThat(market.dropCountBand()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("시장 평균이 내 실적보다 신뢰도가 높으면 기본 기준이 되고, 시장 구간으로 범위를 계산한다")
    void calculate_usesMarketFromSample() {
        // given: 시장 DROP 10개·판매자 5명, WISH 40명 중 10명 구매 → 신뢰도 LOW, 구간 0.14~0.41
        MarketConversion market = DemandPlanCalculator.toMarketConversion(new MarketSample(10, 5, 40, 10, 10), AS_OF);

        // when
        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                100, ConversionSample.empty(), market, THREE_OPTIONS, DemandCalculateRequest.defaults());

        // then: base = 100 × 0.25 × 1.0
        assertThat(result.assumptions().conversionRate())
                .isEqualTo(new Assumption(new BigDecimal("0.25"), AssumptionSource.MARKET));
        assertThat(result.plan().low()).isEqualTo(14);
        assertThat(result.plan().expected()).isEqualTo(25);
        assertThat(result.plan().high()).isEqualTo(41);
        assertThat(result.dataGaps()).containsExactly(DemandDataGap.SMALL_CONVERSION_SAMPLE);
    }
}
