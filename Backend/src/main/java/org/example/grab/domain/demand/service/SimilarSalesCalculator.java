package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.SimilarSalesResponse.OthersSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OwnSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SimilarSample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/*
    비슷한 DROP 실적 지표(DEMAND.md 7.3). 결정적 집계이며 LLM을 쓰지 않는다.
    다른 판매자 표본은 1.3의 최소 집계 단위(판매자 5명·DROP 10개)를 넘을 때만 값을 내고, 비율은 정수 %(소수 둘째 자리),
    매진 시간은 5분 단위로 반올림해 개별 DROP의 수치가 드러나지 않게 한다.
 */
final class SimilarSalesCalculator {

    static final int MIN_MARKET_SELLERS = 5;
    static final int MIN_MARKET_DROPS = 10;
    static final int MARKET_DROP_BAND = 20;
    private static final int MINUTE_UNIT = 5;

    private SimilarSalesCalculator() {
    }

    static OwnSimilarSales own(List<SimilarSample> samples, Selection selection) {
        return new OwnSimilarSales(
                selection,
                samples.size(),
                soldOutRate(samples),
                medianSellOutMinutes(samples),
                sellThroughRate(samples),
                soldPerWish(samples));
    }

    static OthersSimilarSales others(List<SimilarSample> samples, Selection selection, Scope scope, OffsetDateTime asOf) {
        long sellers = samples.stream().map(SimilarSample::sellerId).distinct().count();
        if (sellers < MIN_MARKET_SELLERS || samples.size() < MIN_MARKET_DROPS) {
            return new OthersSimilarSales(selection, scope, "NONE", null, null, null, null, asOf);
        }
        Integer minutes = medianSellOutMinutes(samples);
        return new OthersSimilarSales(
                selection,
                scope,
                samples.size() >= MARKET_DROP_BAND ? "20_PLUS" : "10_TO_19",
                soldOutRate(samples),
                minutes == null ? null : Math.max(MINUTE_UNIT, Math.round(minutes / (float) MINUTE_UNIT) * MINUTE_UNIT),
                sellThroughRate(samples),
                soldPerWish(samples),
                asOf);
    }

    private static BigDecimal soldOutRate(List<SimilarSample> samples) {
        long soldOut = samples.stream().filter(SimilarSample::soldOut).count();
        return ratio(soldOut, samples.size());
    }

    private static BigDecimal sellThroughRate(List<SimilarSample> samples) {
        return ratio(samples.stream().mapToLong(SimilarSample::sold).sum(),
                samples.stream().mapToLong(SimilarSample::prepared).sum());
    }

    // 판매 시작 기록이 있는 DROP만 센다. 기록이 없거나 WISH 합이 0이면 null이다
    private static BigDecimal soldPerWish(List<SimilarSample> samples) {
        List<SimilarSample> recorded = samples.stream().filter(sample -> sample.wishAtStart() != null).toList();
        long wishes = recorded.stream().mapToLong(SimilarSample::wishAtStart).sum();
        if (wishes == 0) {
            return null;
        }
        return ratio(recorded.stream().mapToLong(SimilarSample::sold).sum(), wishes);
    }

    // 매진된 DROP의 매진까지 걸린 시간(분)의 중간값. nearest-rank(5.2와 같은 방식)
    private static Integer medianSellOutMinutes(List<SimilarSample> samples) {
        List<Integer> minutes = samples.stream()
                .filter(SimilarSample::soldOut)
                .map(SimilarSample::sellOutMinutes)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (minutes.isEmpty()) {
            return null;
        }
        return minutes.get((int) Math.ceil(minutes.size() * 0.5) - 1);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
