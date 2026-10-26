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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final BigDecimal SELLER_INPUT_RANGE = new BigDecimal("0.3");
    private static final BigDecimal HIGH_RANGE = new BigDecimal("0.2");
    private static final BigDecimal LOW_RANGE = new BigDecimal("0.4");

    private DemandPlanCalculator() {
    }

    public record Result(OwnConversion own, Assumptions assumptions, Plan plan, List<DemandDataGap> dataGaps) {
    }

    private record BasisAssumption(Assumption rate, Assumption avgQuantity) {
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

        Assumption conversionRate = null;
        Assumption avgQuantity = null;
        BigDecimal range = null;
        if (request.conversionRate() != null) {
            conversionRate = new Assumption(request.conversionRate(), AssumptionSource.SELLER_INPUT);
            avgQuantity = defaultBasis == null
                    ? new Assumption(DEFAULT_AVG_QUANTITY, AssumptionSource.DEFAULT)
                    : basisAssumption(defaultBasis, own, market).avgQuantity();
            range = SELLER_INPUT_RANGE;
        } else {
            ConversionBasis basis = request.conversionBasis() != null ? request.conversionBasis() : defaultBasis;
            ConversionConfidence confidence = basis == null ? ConversionConfidence.NONE : confidenceOf(basis, own, market);
            if (confidence == ConversionConfidence.NONE) {
                dataGaps.add(DemandDataGap.NO_CONVERSION_SAMPLE);
            } else {
                BasisAssumption selected = basisAssumption(basis, own, market);
                conversionRate = selected.rate();
                avgQuantity = selected.avgQuantity();
                range = confidence == ConversionConfidence.HIGH ? HIGH_RANGE : LOW_RANGE;
                if (confidence == ConversionConfidence.LOW) {
                    dataGaps.add(DemandDataGap.SMALL_CONVERSION_SAMPLE);
                }
            }
        }

        Assumption safetyStockRate = request.safetyStockRate() == null
                ? new Assumption(DEFAULT_SAFETY_STOCK_RATE, AssumptionSource.DEFAULT)
                : new Assumption(request.safetyStockRate(), AssumptionSource.SELLER_INPUT);
        Assumptions assumptions = new Assumptions(conversionRate, avgQuantity, safetyStockRate);

        BigDecimal base = activeWishCount == 0 || conversionRate == null
                ? null
                : BigDecimal.valueOf(activeWishCount).multiply(conversionRate.value()).multiply(avgQuantity.value());
        return new Result(own, assumptions, toPlan(base, range, safetyStockRate.value(), options), List.copyOf(dataGaps));
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

    private static OwnConversion toOwnConversion(ConversionSample sample) {
        ConversionConfidence confidence = confidenceOf(sample.dropCount(), sample.wishers());
        if (confidence == ConversionConfidence.NONE) {
            return new OwnConversion(null, null, confidence, sample.dropCount(), sample.wishers());
        }
        BigDecimal rate = BigDecimal.valueOf(sample.converted())
                .divide(BigDecimal.valueOf(sample.wishers()), 2, RoundingMode.HALF_UP);
        BigDecimal avgQuantity = sample.converted() == 0
                ? DEFAULT_AVG_QUANTITY
                : BigDecimal.valueOf(sample.quantity())
                        .divide(BigDecimal.valueOf(sample.converted()), 1, RoundingMode.HALF_UP);
        return new OwnConversion(rate, avgQuantity, confidence, sample.dropCount(), sample.wishers());
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

    private static BasisAssumption basisAssumption(ConversionBasis basis, OwnConversion own, MarketConversion market) {
        if (basis == ConversionBasis.OWN) {
            return new BasisAssumption(
                    new Assumption(own.rate(), AssumptionSource.OWN),
                    new Assumption(own.avgQuantityPerBuyer(), AssumptionSource.OWN));
        }
        return new BasisAssumption(
                new Assumption(market.rate(), AssumptionSource.MARKET),
                new Assumption(market.avgQuantityPerBuyer(), AssumptionSource.MARKET));
    }

    private static Plan toPlan(BigDecimal base, BigDecimal range, BigDecimal safetyStockRate, List<DemandOption> options) {
        int currentTotal = options.stream().mapToInt(DemandOption::totalQuantity).sum();
        if (base == null) {
            List<OptionPlan> optionPlans = options.stream()
                    .map(option -> new OptionPlan(option.optionId(), option.name(), option.totalQuantity(), null))
                    .toList();
            return new Plan(null, null, null, null, currentTotal, null, optionPlans);
        }
        int low = base.multiply(BigDecimal.ONE.subtract(range)).setScale(0, RoundingMode.FLOOR).intValueExact();
        int expected = base.setScale(0, RoundingMode.HALF_UP).intValueExact();
        int high = base.multiply(BigDecimal.ONE.add(range)).setScale(0, RoundingMode.CEILING).intValueExact();
        int recommended = base.multiply(BigDecimal.ONE.add(safetyStockRate))
                .setScale(0, RoundingMode.CEILING).intValueExact();
        return new Plan(low, expected, high, recommended, currentTotal, recommended - currentTotal,
                allocate(recommended, options, currentTotal));
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
