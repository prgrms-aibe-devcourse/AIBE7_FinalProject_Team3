package org.example.grab.domain.demand.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 응답 계약은 DEMAND.md 2.1을 따른다
public record DemandAnalysisResponse(
        long dropId,
        OffsetDateTime calculatedAt,
        String calculationVersion,
        Suggestion suggestion,
        Demand demand,
        Conversion conversion,
        Assumptions assumptions,
        Plan plan,
        List<DemandDataGap> dataGaps,
        List<String> notes
) {

    public record Suggestion(boolean ready) {
    }

    public record Demand(
            long activeWishCount,
            long hoursUntilSaleStart,
            List<DailyWish> dailyWishes,
            boolean trendApproximate
    ) {
    }

    public record DailyWish(LocalDate date, long count) {
    }

    public record Conversion(SampleSelection selection, OwnConversion own, MarketConversion market) {
    }

    // rateLow·rateHigh는 전환율의 95% 윌슨 구간이며 예상 범위(low·high) 계산에 쓴다(DEMAND.md 1.4)
    public record OwnConversion(
            BigDecimal rate,
            BigDecimal rateLow,
            BigDecimal rateHigh,
            BigDecimal avgQuantityPerBuyer,
            ConversionConfidence confidence,
            int dropCount,
            long wishCount
    ) {
    }

    public record MarketConversion(
            BigDecimal rate,
            BigDecimal rateLow,
            BigDecimal rateHigh,
            BigDecimal avgQuantityPerBuyer,
            ConversionConfidence confidence,
            String dropCountBand,
            Long wishCount,
            OffsetDateTime asOf
    ) {
        // 시장 평균 집계(GR-142 ②) 전이거나 최소 집계 단위에 미달한 경우
        public static MarketConversion unavailable() {
            return new MarketConversion(null, null, null, null, ConversionConfidence.NONE, "NONE", null, null);
        }
    }

    public record Assumption(BigDecimal value, AssumptionSource source) {
    }

    public record Assumptions(
            Assumption conversionRate,
            Assumption avgQuantityPerBuyer,
            Assumption safetyStockRate
    ) {
    }

    public record Plan(
            Integer low,
            Integer expected,
            Integer high,
            Integer recommended,
            int currentTotalQuantity,
            Integer differenceFromCurrent,
            List<OptionPlan> options
    ) {
    }

    public record OptionPlan(long optionId, String name, int currentQuantity, Integer recommendedQuantity) {
    }
}
