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
        InitialRecommendation initialRecommendation,
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
        // 최소 집계 단위·신뢰도 기준에 미달하면 값 없이 기준 시점만 내보낸다
        public static MarketConversion unavailable(OffsetDateTime asOf) {
            return new MarketConversion(null, null, null, null, ConversionConfidence.NONE, "NONE", null, asOf);
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

    // 공개 시점 재고 추천 기록(DEMAND.md 5.6) 요약. 생성 단계 추천과 WISH 기준 계획을 비교하는 데 쓴다.
    // 기록에 기준이 없었으면 basis와 수량 필드가 null이다
    public record InitialRecommendation(
            OffsetDateTime recordedAt,
            ConversionBasis basis,
            Integer low,
            Integer expected,
            Integer high,
            Integer recommended,
            int preparedTotal
    ) {
    }
}
