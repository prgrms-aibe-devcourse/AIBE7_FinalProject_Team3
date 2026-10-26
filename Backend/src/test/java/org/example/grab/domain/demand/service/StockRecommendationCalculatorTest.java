package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.StockDataGap;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.MarketSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OptionRecommendation;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OwnSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceBandType;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PricePosition;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StockRecommendationCalculatorTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-26T00:00:00+09:00");
    private static final BigDecimal SAFETY = new BigDecimal("0.10");
    private static final BigDecimal SOLD_OUT_THRESHOLD = new BigDecimal("0.5");
    private static final BigDecimal BAND = new BigDecimal("0.3");

    @Test
    @DisplayName("대표 가격은 입력 수량이 모두 있으면 수량 가중 평균, 하나라도 비면 단순 평균이다")
    void referencePrice_weightsByQuantityOnlyWhenAllEntered() {
        assertThat(StockRecommendationCalculator.referencePrice(new long[]{10000, 20000}, new int[]{3, 1}))
                .isEqualTo(12500);
        assertThat(StockRecommendationCalculator.referencePrice(new long[]{10000, 20000}, new int[]{3, 0}))
                .isEqualTo(15000);
        assertThat(StockRecommendationCalculator.referencePrice(new long[]{10000, 10001}, new int[]{0, 0}))
                .isEqualTo(10001);
    }

    @Test
    @DisplayName("내 실적 분위수는 nearest-rank로 실제 판매량 값 중에서 고르고, 3개 미만이면 계산하지 않는다")
    void ownSales_usesNearestRank() {
        // given: 판매량 10·20·30·40, 준비 수량 50씩, 하나만 품절
        List<SalesSample> samples = List.of(
                sample(1, 10, 50, false), sample(1, 20, 50, false), sample(1, 30, 50, false), sample(1, 40, 40, true));

        // when
        OwnSales own = StockRecommendationCalculator.ownSales(samples);

        // then: ceil(0.25×4)=1번째, ceil(0.5×4)=2번째, ceil(0.75×4)=3번째
        assertThat(own.soldLow()).isEqualTo(10);
        assertThat(own.soldMedian()).isEqualTo(20);
        assertThat(own.soldHigh()).isEqualTo(30);
        assertThat(own.sellThroughRate()).isEqualByComparingTo("0.53");
        assertThat(own.soldOutRate()).isEqualByComparingTo("0.25");
        assertThat(own.confidence()).isEqualTo(ConversionConfidence.LOW);
        assertThat(StockRecommendationCalculator.ownSales(samples.subList(0, 2)).soldMedian()).isNull();
    }

    @Test
    @DisplayName("시장 평균은 판매자 5명·DROP 10개 미만이면 값 없이 기준 시점만 남긴다")
    void marketSales_requiresMinimumUnit() {
        List<SalesSample> fourSellers = IntStream.range(0, 12)
                .mapToObj(i -> sample(i % 4, 20, 40, false)).toList();

        MarketSales market = StockRecommendationCalculator.marketSales(fourSellers, AS_OF);

        assertThat(market.confidence()).isEqualTo(ConversionConfidence.NONE);
        assertThat(market.soldMedian()).isNull();
        assertThat(market.dropCountBand()).isEqualTo("NONE");
        assertThat(market.asOf()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("시장 판매량 분위수는 5개 단위로 하위는 내림, 중간은 반올림, 상위는 올림한다")
    void marketSales_roundsQuantitiesToUnit() {
        // given: 판매량 11~20(판매자 5명), 하위 3번째=13, 중간 5번째=15, 상위 8번째=18
        List<SalesSample> samples = IntStream.rangeClosed(11, 20)
                .mapToObj(sold -> sample(sold % 5, sold, 30, false)).toList();

        MarketSales market = StockRecommendationCalculator.marketSales(samples, AS_OF);

        assertThat(market.soldLow()).isEqualTo(10);
        assertThat(market.soldMedian()).isEqualTo(15);
        assertThat(market.soldHigh()).isEqualTo(20);
        assertThat(market.confidence()).isEqualTo(ConversionConfidence.HIGH);
        assertThat(market.dropCountBand()).isEqualTo("10_TO_19");
    }

    @Test
    @DisplayName("신뢰도가 높은 기준을 쓰고, 준비 권장은 기준 수량에 안전 재고를 더해 올림한 뒤 입력 수량 비율로 나눈다")
    void recommend_usesHigherConfidenceBasis() {
        // given: 내 실적 LOW, 시장 HIGH
        OwnSales own = new OwnSales(10, 20, 30, new BigDecimal("0.60"), new BigDecimal("0.00"),
                ConversionConfidence.LOW, 4);
        MarketSales market = new MarketSales(25, 40, 60, new BigDecimal("0.71"), new BigDecimal("0.30"),
                ConversionConfidence.HIGH, "10_TO_19", AS_OF);

        // when
        StockRecommendationCalculator.Result result = StockRecommendationCalculator.recommend(
                own, market, new int[]{40, 20}, SAFETY, SOLD_OUT_THRESHOLD);

        // then: ceil(40 × 1.1) = 44, 40:20 비율이면 29.33·14.67 → 29·15
        assertThat(result.basis()).isEqualTo(ConversionBasis.MARKET);
        assertThat(result.recommendation().expected()).isEqualTo(40);
        assertThat(result.recommendation().recommended()).isEqualTo(44);
        assertThat(result.recommendation().options()).containsExactly(
                new OptionRecommendation(0, 29), new OptionRecommendation(1, 15));
        assertThat(result.dataGaps()).isEmpty();
    }

    @Test
    @DisplayName("품절이 잦으면 상위 25%를 기준으로 하고, 입력 수량이 비면 균등하게 나누며 나머지는 앞 옵션부터 준다")
    void recommend_usesHighWhenFrequentlySoldOut() {
        OwnSales own = new OwnSales(10, 20, 31, new BigDecimal("0.95"), new BigDecimal("0.50"),
                ConversionConfidence.LOW, 4);

        StockRecommendationCalculator.Result result = StockRecommendationCalculator.recommend(
                own, MarketSales.unavailable(AS_OF), new int[]{0, 5, 0}, SAFETY, SOLD_OUT_THRESHOLD);

        // ceil(31 × 1.1) = 35, 균등 11.67씩 → 12·12·11
        assertThat(result.basis()).isEqualTo(ConversionBasis.OWN);
        assertThat(result.recommendation().expected()).isEqualTo(31);
        assertThat(result.recommendation().recommended()).isEqualTo(35);
        assertThat(result.recommendation().options()).extracting(OptionRecommendation::recommendedQuantity)
                .containsExactly(12, 12, 11);
        assertThat(result.dataGaps()).containsExactly(StockDataGap.SMALL_SALES_SAMPLE, StockDataGap.FREQUENT_SOLD_OUT);
    }

    @Test
    @DisplayName("두 기준 모두 표본이 부족하면 수량 없이 NO_SALES_SAMPLE을 반환한다")
    void recommend_withoutBasis() {
        OwnSales own = new OwnSales(null, null, null, null, null, ConversionConfidence.NONE, 2);

        StockRecommendationCalculator.Result result = StockRecommendationCalculator.recommend(
                own, MarketSales.unavailable(AS_OF), new int[]{10, 10}, SAFETY, SOLD_OUT_THRESHOLD);

        assertThat(result.basis()).isNull();
        assertThat(result.recommendation().recommended()).isNull();
        assertThat(result.recommendation().options()).extracting(OptionRecommendation::recommendedQuantity)
                .containsExactly(null, null);
        assertThat(result.dataGaps()).containsExactly(StockDataGap.NO_SALES_SAMPLE);
    }

    @Test
    @DisplayName("가격대 참고는 1/3·2/3 분위로 위치를 정하고 보호 단위를 채운 가격대만 판매율을 준다")
    void priceReference_positionsAndBands() {
        // given: 판매자 5명, 가격 1만~3만원(30개), 기준 2만원 → 비슷한 가격대 14,000~26,000원
        List<SalesSample> samples = IntStream.range(0, 30)
                .mapToObj(i -> new SalesSample(i % 5, 10000 + i * 690L, 40, 20, false)).toList();

        PriceReference reference = StockRecommendationCalculator.priceReference(20000, samples, BAND);

        assertThat(reference.position()).isEqualTo(PricePosition.MID);
        // 참고 가격 범위: 하위 25%(8번째 14,830원)는 천 원 내림, 상위 25%(23번째 25,180원)는 천 원 올림
        assertThat(reference.typicalPriceLow()).isEqualTo(14000L);
        assertThat(reference.typicalPriceHigh()).isEqualTo(26000L);
        assertThat(reference.bands()).extracting(band -> band.band())
                .containsExactly(PriceBandType.LOWER, PriceBandType.SIMILAR, PriceBandType.HIGHER);
        assertThat(reference.bands().get(1).sellThroughRate()).isEqualByComparingTo("0.50");
        assertThat(reference.bands().get(1).dropCountBand()).isEqualTo("10_TO_19");
        // 1만원대 초반 표본은 6개뿐이라 보호 단위 미달
        assertThat(reference.bands().get(0).sellThroughRate()).isNull();
        assertThat(StockRecommendationCalculator.priceReference(9000, samples, BAND).position())
                .isEqualTo(PricePosition.LOW);
        assertThat(StockRecommendationCalculator.priceReference(0, samples, BAND).bands()).isEmpty();
    }

    @Test
    @DisplayName("다른 판매자 표본이 보호 단위에 못 미치면 참고 가격 범위와 위치를 주지 않는다")
    void priceReference_hidesTypicalRangeBelowMinimumUnit() {
        List<SalesSample> fourSellers = IntStream.range(0, 12)
                .mapToObj(i -> new SalesSample(i % 4, 20000, 40, 20, false)).toList();

        PriceReference reference = StockRecommendationCalculator.priceReference(20000, fourSellers, BAND);

        assertThat(reference.position()).isEqualTo(PricePosition.UNKNOWN);
        assertThat(reference.typicalPriceLow()).isNull();
        assertThat(reference.typicalPriceHigh()).isNull();
    }

    private static SalesSample sample(long sellerId, int sold, int prepared, boolean soldOut) {
        return new SalesSample(sellerId, 10000, prepared, sold, soldOut);
    }
}
