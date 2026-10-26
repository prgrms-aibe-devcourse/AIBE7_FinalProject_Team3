package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumptions;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.MarketConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OwnConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Plan;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandDataGap;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.MarketSample;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
    판매 계획 계산식(DEMAND.md 1.4~1.7). DB·시각에 의존하지 않는 결정적 함수라 같은 입력이면 항상 같은 결과를 낸다.
    소수 계산은 BigDecimal로 해서 반올림 경계에서 double 오차로 수량이 1씩 흔들리지 않게 한다.
 */
public final class DemandPlanCalculator {

    static final BigDecimal DEFAULT_SAFETY_STOCK_RATE = new BigDecimal("0.1");
    private static final BigDecimal DEFAULT_AVG_QUANTITY = new BigDecimal("1.0");
    // 판매자 입력 전환율은 표본이 없어 통계 구간을 만들 수 없으므로 가정한 폭을 쓴다(DEMAND.md 1.9)
    private static final BigDecimal SELLER_INPUT_RANGE = new BigDecimal("0.3");
    // 95% 신뢰수준의 정규분포 임계값
    private static final BigDecimal Z = new BigDecimal("1.96");
    private static final MathContext PRECISION = MathContext.DECIMAL64;
    // 시장 평균 최소 집계 단위(DEMAND.md 1.3). 개별 판매자 실적이 드러나지 않게 한다.
    static final int MARKET_MIN_DROPS = 10;
    static final int MARKET_MIN_SELLERS = 5;

    private DemandPlanCalculator() {
    }

    public record Result(OwnConversion own, Assumptions assumptions, Plan plan, List<DemandDataGap> dataGaps) {
    }

    record RateInterval(BigDecimal low, BigDecimal high) {
    }

    private record SelectedRate(Assumption rate, Assumption avgQuantity, RateInterval interval) {
    }

    public static Result calculate(
            long activeWishCount,
            ConversionSample ownSample,
            MarketConversion market,
            List<DemandOption> options,
            DemandCalculateRequest request) {
        OwnConversion own = toOwnConversion(ownSample);
        ConversionBasis defaultBasis = defaultBasis(own.confidence(), market.confidence());
        List<DemandDataGap> dataGaps = new ArrayList<>();
        if (activeWishCount == 0) {
            dataGaps.add(DemandDataGap.NO_ACTIVE_WISH);
        }

        SelectedRate selected = null;
        if (request.conversionRate() != null) {
            BigDecimal rate = request.conversionRate();
            Assumption avgQuantity = defaultBasis == null
                    ? new Assumption(DEFAULT_AVG_QUANTITY, AssumptionSource.DEFAULT)
                    : basisRate(defaultBasis, own, market).avgQuantity();
            selected = new SelectedRate(new Assumption(rate, AssumptionSource.SELLER_INPUT), avgQuantity,
                    new RateInterval(
                            rate.multiply(BigDecimal.ONE.subtract(SELLER_INPUT_RANGE)),
                            rate.multiply(BigDecimal.ONE.add(SELLER_INPUT_RANGE)).min(BigDecimal.ONE)));
        } else {
            ConversionBasis basis = request.conversionBasis() != null ? request.conversionBasis() : defaultBasis;
            ConversionConfidence confidence = basis == null ? ConversionConfidence.NONE : confidenceOf(basis, own, market);
            if (confidence == ConversionConfidence.NONE) {
                dataGaps.add(DemandDataGap.NO_CONVERSION_SAMPLE);
            } else {
                selected = basisRate(basis, own, market);
                if (confidence == ConversionConfidence.LOW) {
                    dataGaps.add(DemandDataGap.SMALL_CONVERSION_SAMPLE);
                }
            }
        }

        Assumption safetyStockRate = request.safetyStockRate() == null
                ? new Assumption(DEFAULT_SAFETY_STOCK_RATE, AssumptionSource.DEFAULT)
                : new Assumption(request.safetyStockRate(), AssumptionSource.SELLER_INPUT);
        Assumptions assumptions = selected == null
                ? new Assumptions(null, null, safetyStockRate)
                : new Assumptions(selected.rate(), selected.avgQuantity(), safetyStockRate);
        Plan plan = activeWishCount == 0 || selected == null
                ? emptyPlan(options)
                : toPlan(activeWishCount, selected, safetyStockRate.value(), options);
        return new Result(own, assumptions, plan, List.copyOf(dataGaps));
    }

    static ConversionConfidence confidenceOf(int dropCount, long wishers) {
        if (dropCount >= 10 && wishers >= 100) {
            return ConversionConfidence.HIGH;
        }
        if (dropCount >= 3 && wishers >= 20) {
            return ConversionConfidence.LOW;
        }
        return ConversionConfidence.NONE;
    }

    /*
        전환율의 95% 윌슨 점수 구간. 표본이 적을수록 넓어지고, 전환율이 0이나 1에 가까워도 구간이 0~1을 벗어나지 않는다.
        응답에 보이는 값으로 검산할 수 있게 소수 둘째 자리로 맞추되, 범위가 좁아지지 않도록 하한은 내림·상한은 올림한다.
     */
    static RateInterval wilsonInterval(long converted, long wishers) {
        BigDecimal n = BigDecimal.valueOf(wishers);
        BigDecimal p = BigDecimal.valueOf(converted).divide(n, PRECISION);
        BigDecimal zSquared = Z.multiply(Z);
        BigDecimal denominator = BigDecimal.ONE.add(zSquared.divide(n, PRECISION));
        BigDecimal center = p.add(zSquared.divide(n.multiply(BigDecimal.valueOf(2)), PRECISION))
                .divide(denominator, PRECISION);
        BigDecimal variance = p.multiply(BigDecimal.ONE.subtract(p)).divide(n, PRECISION)
                .add(zSquared.divide(n.multiply(n).multiply(BigDecimal.valueOf(4)), PRECISION));
        BigDecimal halfWidth = Z.multiply(variance.sqrt(PRECISION)).divide(denominator, PRECISION);
        return new RateInterval(
                center.subtract(halfWidth).max(BigDecimal.ZERO).setScale(2, RoundingMode.FLOOR),
                center.add(halfWidth).min(BigDecimal.ONE).setScale(2, RoundingMode.CEILING));
    }

    /*
        다른 판매자 표본 합계를 응답·계산용 시장 평균으로 바꾼다(DEMAND.md 1.3).
        판매자 5명·DROP 10개 미만이면 값을 만들지 않고, 표본 수는 구간으로, WISH 수는 10 단위 내림으로만 내보낸다.
        전환율은 정수 %로 반올림하고 구간은 원래 집계값으로 계산한 뒤 정수 %로 하한 내림·상한 올림한다.
     */
    public static MarketConversion toMarketConversion(MarketSample sample, OffsetDateTime asOf) {
        if (sample.dropCount() < MARKET_MIN_DROPS || sample.sellerCount() < MARKET_MIN_SELLERS) {
            return MarketConversion.unavailable(asOf);
        }
        ConversionConfidence confidence = confidenceOf(sample.dropCount(), sample.wishers());
        if (confidence == ConversionConfidence.NONE) {
            return MarketConversion.unavailable(asOf);
        }
        BigDecimal rate = BigDecimal.valueOf(sample.converted())
                .divide(BigDecimal.valueOf(sample.wishers()), 2, RoundingMode.HALF_UP);
        RateInterval interval = wilsonInterval(sample.converted(), sample.wishers());
        BigDecimal avgQuantity = sample.converted() == 0
                ? DEFAULT_AVG_QUANTITY
                : BigDecimal.valueOf(sample.quantity())
                        .divide(BigDecimal.valueOf(sample.converted()), 1, RoundingMode.HALF_UP);
        String dropCountBand = sample.dropCount() >= 20 ? "20_PLUS" : "10_TO_19";
        return new MarketConversion(rate, interval.low(), interval.high(), avgQuantity, confidence,
                dropCountBand, sample.wishers() / 10 * 10, asOf);
    }

    private static OwnConversion toOwnConversion(ConversionSample sample) {
        ConversionConfidence confidence = confidenceOf(sample.dropCount(), sample.wishers());
        if (confidence == ConversionConfidence.NONE) {
            return new OwnConversion(null, null, null, null, confidence, sample.dropCount(), sample.wishers());
        }
        BigDecimal rate = BigDecimal.valueOf(sample.converted())
                .divide(BigDecimal.valueOf(sample.wishers()), 2, RoundingMode.HALF_UP);
        RateInterval interval = wilsonInterval(sample.converted(), sample.wishers());
        BigDecimal avgQuantity = sample.converted() == 0
                ? DEFAULT_AVG_QUANTITY
                : BigDecimal.valueOf(sample.quantity())
                        .divide(BigDecimal.valueOf(sample.converted()), 1, RoundingMode.HALF_UP);
        return new OwnConversion(rate, interval.low(), interval.high(), avgQuantity, confidence,
                sample.dropCount(), sample.wishers());
    }

    // 신뢰도가 높은 기준을 쓰고, 같으면 본인 데이터가 더 직접적인 근거이므로 내 실적을 쓴다
    private static ConversionBasis defaultBasis(ConversionConfidence own, ConversionConfidence market) {
        if (own == ConversionConfidence.NONE && market == ConversionConfidence.NONE) {
            return null;
        }
        return rank(own) >= rank(market) ? ConversionBasis.OWN : ConversionBasis.MARKET;
    }

    private static int rank(ConversionConfidence confidence) {
        return switch (confidence) {
            case HIGH -> 2;
            case LOW -> 1;
            case NONE -> 0;
        };
    }

    private static ConversionConfidence confidenceOf(ConversionBasis basis, OwnConversion own, MarketConversion market) {
        return basis == ConversionBasis.OWN ? own.confidence() : market.confidence();
    }

    private static SelectedRate basisRate(ConversionBasis basis, OwnConversion own, MarketConversion market) {
        if (basis == ConversionBasis.OWN) {
            return new SelectedRate(
                    new Assumption(own.rate(), AssumptionSource.OWN),
                    new Assumption(own.avgQuantityPerBuyer(), AssumptionSource.OWN),
                    new RateInterval(own.rateLow(), own.rateHigh()));
        }
        return new SelectedRate(
                new Assumption(market.rate(), AssumptionSource.MARKET),
                new Assumption(market.avgQuantityPerBuyer(), AssumptionSource.MARKET),
                new RateInterval(market.rateLow(), market.rateHigh()));
    }

    private static Plan emptyPlan(List<DemandOption> options) {
        List<OptionPlan> optionPlans = options.stream()
                .map(option -> new OptionPlan(option.optionId(), option.name(), option.totalQuantity(), null))
                .toList();
        return new Plan(null, null, null, null, currentTotal(options), null, optionPlans);
    }

    private static Plan toPlan(long activeWishCount, SelectedRate selected, BigDecimal safetyStockRate,
                               List<DemandOption> options) {
        BigDecimal wishes = BigDecimal.valueOf(activeWishCount);
        BigDecimal avgQuantity = selected.avgQuantity().value();
        BigDecimal base = wishes.multiply(selected.rate().value()).multiply(avgQuantity);
        int low = wishes.multiply(selected.interval().low()).multiply(avgQuantity)
                .setScale(0, RoundingMode.FLOOR).intValueExact();
        int expected = base.setScale(0, RoundingMode.HALF_UP).intValueExact();
        int high = wishes.multiply(selected.interval().high()).multiply(avgQuantity)
                .setScale(0, RoundingMode.CEILING).intValueExact();
        int recommended = base.multiply(BigDecimal.ONE.add(safetyStockRate))
                .setScale(0, RoundingMode.CEILING).intValueExact();
        int currentTotal = currentTotal(options);
        return new Plan(low, expected, high, recommended, currentTotal, recommended - currentTotal,
                allocate(recommended, options, currentTotal));
    }

    private static int currentTotal(List<DemandOption> options) {
        return options.stream().mapToInt(DemandOption::totalQuantity).sum();
    }

    /*
        추천 수량을 현재 옵션별 판매 수량 비율로 나눈다(최대 나머지 방식).
        몫의 나머지가 큰 옵션부터 남은 수량을 1개씩 주고, 나머지가 같으면 sort_order, id 순이다.
        현재 수량이 모두 0이면 비율을 알 수 없으므로 똑같이 나눈다.
     */
    static List<OptionPlan> allocate(int recommended, List<DemandOption> options, int currentTotal) {
        if (options.isEmpty()) {
            return List.of();
        }
        long denominator = currentTotal == 0 ? options.size() : currentTotal;
        record Share(DemandOption option, int quota, long remainder) {
        }
        List<Share> shares = options.stream().map(option -> {
            long numerator = (long) recommended * (currentTotal == 0 ? 1 : option.totalQuantity());
            return new Share(option, (int) (numerator / denominator), numerator % denominator);
        }).toList();
        int leftover = recommended - shares.stream().mapToInt(Share::quota).sum();
        Set<Long> bonusOptionIds = shares.stream()
                .sorted(Comparator.comparingLong(Share::remainder).reversed()
                        .thenComparingInt(share -> share.option().sortOrder())
                        .thenComparingLong(share -> share.option().optionId()))
                .limit(leftover)
                .map(share -> share.option().optionId())
                .collect(Collectors.toSet());
        return shares.stream()
                .map(share -> new OptionPlan(
                        share.option().optionId(),
                        share.option().name(),
                        share.option().totalQuantity(),
                        share.quota() + (bonusOptionIds.contains(share.option().optionId()) ? 1 : 0)))
                .toList();
    }
}
