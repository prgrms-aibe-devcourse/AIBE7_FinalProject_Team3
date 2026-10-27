package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.StockDataGap;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.MarketSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OptionRecommendation;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OwnSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceBand;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceBandType;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PricePosition;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceReference;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.Recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/*
    생성 단계 재고 추천 계산(DEMAND.md 5.2·5.4). 같은 입력이면 항상 같은 결과를 내는 순수 함수이며 LLM을 쓰지 않는다.
    신뢰도 기준·시장 최소 집계 단위·옵션 배분은 WISH 단계 계산(DemandPlanCalculator)과 같은 규칙을 쓴다.
 */
public final class StockRecommendationCalculator {

    static final String CALCULATION_VERSION = "s1";
    // 시장 판매량 분위수는 개별 DROP 판매량이 그대로 드러나지 않게 5개 단위로 맞춘다(5.2)
    static final int MARKET_QUANTITY_UNIT = 5;
    // 참고 가격 범위도 같은 이유로 천 원 단위로 맞춘다(5.4)
    static final int MARKET_PRICE_UNIT = 1000;
    private static final int HIGH_MIN_DROPS = 10;
    private static final int LOW_MIN_DROPS = 3;

    private StockRecommendationCalculator() {
    }

    public record Result(
            ConversionBasis basis,
            Recommendation recommendation,
            List<StockDataGap> dataGaps
    ) {
    }

    // 모든 옵션의 입력 수량이 1 이상이면 준비 수량 가중 평균, 아니면 단순 평균. 원 단위 반올림
    static long referencePrice(long[] unitPrices, int[] quantities) {
        boolean weighted = quantities != null && Arrays.stream(quantities).allMatch(quantity -> quantity >= 1);
        BigDecimal sum = BigDecimal.ZERO;
        long weight = 0;
        for (int i = 0; i < unitPrices.length; i++) {
            int quantity = weighted ? quantities[i] : 1;
            sum = sum.add(BigDecimal.valueOf(unitPrices[i]).multiply(BigDecimal.valueOf(quantity)));
            weight += quantity;
        }
        return sum.divide(BigDecimal.valueOf(weight), 0, RoundingMode.HALF_UP).longValueExact();
    }

    static OwnSales ownSales(List<SalesSample> samples) {
        ConversionConfidence confidence = confidenceOf(samples.size());
        if (confidence == ConversionConfidence.NONE) {
            return new OwnSales(null, null, null, null, null, confidence, samples.size());
        }
        int[] sold = sortedSold(samples);
        return new OwnSales(
                nearestRank(sold, 1, 4),
                nearestRank(sold, 1, 2),
                nearestRank(sold, 3, 4),
                sellThrough(samples),
                soldOutRate(samples),
                confidence,
                samples.size());
    }

    // 최소 집계 단위(판매자 5명·DROP 10개) 미달이면 값 없이 기준 시점만 남긴다(1.3)
    static MarketSales marketSales(List<SalesSample> samples, OffsetDateTime asOf) {
        if (!protectable(samples)) {
            return MarketSales.unavailable(asOf);
        }
        int[] sold = sortedSold(samples);
        return new MarketSales(
                roundTo(nearestRank(sold, 1, 4), RoundingMode.FLOOR),
                roundTo(nearestRank(sold, 1, 2), RoundingMode.HALF_UP),
                roundTo(nearestRank(sold, 3, 4), RoundingMode.CEILING),
                sellThrough(samples),
                soldOutRate(samples),
                confidenceOf(samples.size()),
                dropCountBand(samples.size()),
                asOf);
    }

    /*
        기본 기준은 신뢰도가 높은 쪽, 같으면 내 실적이다. 품절 비율이 기준 이상이면 품절된 DROP의 판매량이 수요의 하한이라
        중간값 대신 상위 25%를 기준 수량으로 쓴다. 준비 권장은 올림, 옵션별 배분은 최대 나머지 방식이다.
     */
    static Result recommend(OwnSales own, MarketSales market, int[] quantities, BigDecimal safetyStockRate,
                            BigDecimal soldOutThreshold) {
        int optionCount = quantities.length;
        ConversionBasis basis = basisOf(own.confidence(), market.confidence());
        if (basis == null) {
            List<OptionRecommendation> empty = IntStream.range(0, optionCount)
                    .mapToObj(index -> new OptionRecommendation(index, null)).toList();
            return new Result(null,
                    new Recommendation(null, null, null, null, safetyStockRate, empty),
                    List.of(StockDataGap.NO_SALES_SAMPLE));
        }
        boolean useOwn = basis == ConversionBasis.OWN;
        int low = useOwn ? own.soldLow() : market.soldLow();
        int median = useOwn ? own.soldMedian() : market.soldMedian();
        int high = useOwn ? own.soldHigh() : market.soldHigh();
        BigDecimal soldOutRate = useOwn ? own.soldOutRate() : market.soldOutRate();
        ConversionConfidence confidence = useOwn ? own.confidence() : market.confidence();

        boolean frequentSoldOut = soldOutRate.compareTo(soldOutThreshold) >= 0;
        int expected = frequentSoldOut ? high : median;
        int recommended = BigDecimal.valueOf(expected)
                .multiply(BigDecimal.ONE.add(safetyStockRate))
                .setScale(0, RoundingMode.CEILING)
                .intValueExact();

        List<StockDataGap> dataGaps = new ArrayList<>();
        if (confidence == ConversionConfidence.LOW) {
            dataGaps.add(StockDataGap.SMALL_SALES_SAMPLE);
        }
        if (frequentSoldOut) {
            dataGaps.add(StockDataGap.FREQUENT_SOLD_OUT);
        }
        return new Result(basis,
                new Recommendation(low, expected, high, recommended, safetyStockRate, allocate(recommended, quantities)),
                List.copyOf(dataGaps));
    }

    /*
        가격대 참고(5.4). 다른 판매자 표본의 대표 가격 1/3·2/3 분위와 비교해 위치를 정하고,
        하위 25%~상위 25% 가격을 참고 가격 범위로 준다. 개별 DROP 가격이 그대로 드러나지 않게
        참고 가격은 천 원 단위로 하한은 내림·상한은 올림하고, 위치 판단에 쓴 분위 가격은 내보내지 않는다.
        낮은·비슷한·높은 가격대마다 보호 단위를 채운 구간만 판매율을 준다. 가격을 추천하지는 않는다.
     */
    static PriceReference priceReference(long referencePrice, List<SalesSample> samples, BigDecimal priceBandRatio) {
        if (referencePrice == 0) {
            return new PriceReference(PricePosition.UNKNOWN, null, null, List.of());
        }
        PricePosition position = PricePosition.UNKNOWN;
        Long typicalLow = null;
        Long typicalHigh = null;
        if (protectable(samples)) {
            long[] prices = samples.stream().mapToLong(SalesSample::referencePrice).sorted().toArray();
            long lowerThird = prices[rankIndex(prices.length, 1, 3)];
            long upperThird = prices[rankIndex(prices.length, 2, 3)];
            position = referencePrice <= lowerThird ? PricePosition.LOW
                    : referencePrice >= upperThird ? PricePosition.HIGH
                    : PricePosition.MID;
            typicalLow = roundPrice(prices[rankIndex(prices.length, 1, 4)], RoundingMode.FLOOR);
            typicalHigh = roundPrice(prices[rankIndex(prices.length, 3, 4)], RoundingMode.CEILING);
        }
        BigDecimal reference = BigDecimal.valueOf(referencePrice);
        BigDecimal min = reference.multiply(BigDecimal.ONE.subtract(priceBandRatio));
        BigDecimal max = reference.multiply(BigDecimal.ONE.add(priceBandRatio));
        return new PriceReference(position, typicalLow, typicalHigh, List.of(
                band(PriceBandType.LOWER, samples, sample -> price(sample).compareTo(min) < 0),
                band(PriceBandType.SIMILAR, samples,
                        sample -> price(sample).compareTo(min) >= 0 && price(sample).compareTo(max) <= 0),
                band(PriceBandType.HIGHER, samples, sample -> price(sample).compareTo(max) > 0)));
    }

    static ConversionConfidence confidenceOf(int dropCount) {
        if (dropCount >= HIGH_MIN_DROPS) {
            return ConversionConfidence.HIGH;
        }
        return dropCount >= LOW_MIN_DROPS ? ConversionConfidence.LOW : ConversionConfidence.NONE;
    }

    private static PriceBand band(PriceBandType type, List<SalesSample> samples, Predicate<SalesSample> inBand) {
        List<SalesSample> members = samples.stream().filter(inBand).toList();
        if (!protectable(members)) {
            return new PriceBand(type, null, "NONE");
        }
        return new PriceBand(type, sellThrough(members), dropCountBand(members.size()));
    }

    private static BigDecimal price(SalesSample sample) {
        return BigDecimal.valueOf(sample.referencePrice());
    }

    private static ConversionBasis basisOf(ConversionConfidence own, ConversionConfidence market) {
        if (own == ConversionConfidence.NONE && market == ConversionConfidence.NONE) {
            return null;
        }
        return rank(market) > rank(own) ? ConversionBasis.MARKET : ConversionBasis.OWN;
    }

    private static int rank(ConversionConfidence confidence) {
        return switch (confidence) {
            case HIGH -> 2;
            case LOW -> 1;
            case NONE -> 0;
        };
    }

    // 모든 옵션의 입력 수량이 1 이상이면 그 비율, 아니면 균등하게 나눈다. 나머지가 같으면 앞 옵션부터 준다
    private static List<OptionRecommendation> allocate(int recommended, int[] quantities) {
        boolean proportional = Arrays.stream(quantities).allMatch(quantity -> quantity >= 1);
        List<DemandOption> options = IntStream.range(0, quantities.length)
                .mapToObj(index -> new DemandOption(index, null, proportional ? quantities[index] : 0, index))
                .toList();
        int total = proportional ? Arrays.stream(quantities).sum() : 0;
        List<OptionPlan> plans = DemandPlanCalculator.allocate(recommended, options, total);
        return plans.stream()
                .map(plan -> new OptionRecommendation((int) plan.optionId(), plan.recommendedQuantity()))
                .toList();
    }

    private static boolean protectable(List<SalesSample> samples) {
        return samples.size() >= DemandPlanCalculator.MARKET_MIN_DROPS
                && samples.stream().map(SalesSample::sellerId).distinct().count()
                >= DemandPlanCalculator.MARKET_MIN_SELLERS;
    }

    private static int[] sortedSold(List<SalesSample> samples) {
        return samples.stream().mapToInt(SalesSample::sold).sorted().toArray();
    }

    // nearest-rank: 정렬한 값의 ceil(k × n)번째(1부터)
    private static int nearestRank(int[] sorted, int numerator, int denominator) {
        return sorted[rankIndex(sorted.length, numerator, denominator)];
    }

    private static int rankIndex(int size, int numerator, int denominator) {
        int rank = (int) Math.ceil((double) numerator * size / denominator);
        return Math.max(rank, 1) - 1;
    }

    private static BigDecimal sellThrough(List<SalesSample> samples) {
        long sold = samples.stream().mapToLong(SalesSample::sold).sum();
        long prepared = samples.stream().mapToLong(SalesSample::prepared).sum();
        return BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(prepared), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal soldOutRate(List<SalesSample> samples) {
        long soldOut = samples.stream().filter(SalesSample::soldOut).count();
        return BigDecimal.valueOf(soldOut).divide(BigDecimal.valueOf(samples.size()), 2, RoundingMode.HALF_UP);
    }

    private static long roundPrice(long price, RoundingMode mode) {
        return BigDecimal.valueOf(price)
                .divide(BigDecimal.valueOf(MARKET_PRICE_UNIT), 0, mode)
                .multiply(BigDecimal.valueOf(MARKET_PRICE_UNIT))
                .longValueExact();
    }

    private static int roundTo(int quantity, RoundingMode mode) {
        return BigDecimal.valueOf(quantity)
                .divide(BigDecimal.valueOf(MARKET_QUANTITY_UNIT), 0, mode)
                .multiply(BigDecimal.valueOf(MARKET_QUANTITY_UNIT))
                .intValueExact();
    }

    private static String dropCountBand(int dropCount) {
        return dropCount >= 20 ? "20_PLUS" : "10_TO_19";
    }
}
