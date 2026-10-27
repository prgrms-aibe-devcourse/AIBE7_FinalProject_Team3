package org.example.grab.domain.demand.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

// 응답 계약은 DEMAND.md 5.5를 따른다. 시장 평균은 1.3의 보호 규칙을 적용한 값만 담는다
public record StockRecommendationResponse(
        OffsetDateTime calculatedAt,
        String calculationVersion,
        long referencePrice,
        OwnSales own,
        MarketSales market,
        ConversionBasis basis,
        Recommendation recommendation,
        PriceReference priceReference,
        List<StockDataGap> dataGaps
) {

    public record OwnSales(
            Integer soldLow,
            Integer soldMedian,
            Integer soldHigh,
            BigDecimal sellThroughRate,
            BigDecimal soldOutRate,
            ConversionConfidence confidence,
            int dropCount
    ) {
    }

    public record MarketSales(
            Integer soldLow,
            Integer soldMedian,
            Integer soldHigh,
            BigDecimal sellThroughRate,
            BigDecimal soldOutRate,
            ConversionConfidence confidence,
            String dropCountBand,
            OffsetDateTime asOf
    ) {
        public static MarketSales unavailable(OffsetDateTime asOf) {
            return new MarketSales(null, null, null, null, null, ConversionConfidence.NONE, "NONE", asOf);
        }
    }

    // expected는 기준 수량(중간값, 품절이 잦으면 상위 25%)이다. 기준이 없으면 수량 필드가 null이다
    public record Recommendation(
            Integer low,
            Integer expected,
            Integer high,
            Integer recommended,
            BigDecimal safetyStockRate,
            List<OptionRecommendation> options
    ) {
    }

    // index는 요청 옵션 순서다
    public record OptionRecommendation(int index, Integer recommendedQuantity) {
    }

    // typicalPrice는 다른 판매자 DROP 가격의 하위 25%~상위 25%(천 원 단위)이며 추천 가격이 아니다
    public record PriceReference(
            PricePosition position,
            Long typicalPriceLow,
            Long typicalPriceHigh,
            List<PriceBand> bands
    ) {
    }

    public record PriceBand(PriceBandType band, BigDecimal sellThroughRate, String dropCountBand) {
    }

    public enum PricePosition {
        LOW, MID, HIGH, UNKNOWN
    }

    public enum PriceBandType {
        LOWER, SIMILAR, HIGHER
    }
}
