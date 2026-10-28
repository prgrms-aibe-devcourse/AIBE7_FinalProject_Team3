package org.example.grab.domain.demand.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// 응답 계약은 DEMAND.md 7.4를 따른다. 다른 판매자 수치는 1.3의 보호 규칙을 적용한 값만 담는다
public record SimilarSalesResponse(
        @Schema(nullable = true) Basis basis,
        Reason reason,
        @Schema(nullable = true) OwnSimilarSales own,
        OthersSimilarSales others
) {

    // 기준으로 삼은 쪽. 다른 판매자 지표가 보호 단위 미달이라 쓸 수 없으면 null이다
    public enum Basis {
        OWN,
        OTHERS
    }

    // OWN_ENOUGH: 내 비슷한 DROP 기준 이상, OWN_FEW: 1개 이상 기준 미만, OWN_NOT_SIMILAR: 내 종료 DROP은 있지만 비슷한 것 없음,
    // FIRST_SALE: 내 종료 DROP 없음
    public enum Reason {
        OWN_ENOUGH,
        OWN_FEW,
        OWN_NOT_SIMILAR,
        FIRST_SALE
    }

    // SIMILAR: 임베딩 유사도로 고름, ALL: 내 전체 종료 DROP, CATEGORY: 같은 카테고리 전체
    public enum Selection {
        SIMILAR,
        ALL,
        CATEGORY
    }

    // NEW_SELLERS: 비슷한 단계 판매자(종료 DROP 기준 이하), ALL_SELLERS: 전체 다른 판매자
    public enum Scope {
        NEW_SELLERS,
        ALL_SELLERS
    }

    public record OwnSimilarSales(
            Selection selection,
            int dropCount,
            BigDecimal soldOutRate,
            @Schema(nullable = true) Integer medianSellOutMinutes,
            BigDecimal sellThroughRate,
            @Schema(nullable = true) BigDecimal soldPerWish
    ) {
    }

    // 보호 단위 미달이면 dropCountBand가 NONE이고 지표가 null이다
    public record OthersSimilarSales(
            Selection selection,
            Scope scope,
            String dropCountBand,
            @Schema(nullable = true) BigDecimal soldOutRate,
            @Schema(nullable = true) Integer medianSellOutMinutes,
            @Schema(nullable = true) BigDecimal sellThroughRate,
            @Schema(nullable = true) BigDecimal soldPerWish,
            OffsetDateTime asOf
    ) {
        public boolean available() {
            return !"NONE".equals(dropCountBand);
        }
    }
}
