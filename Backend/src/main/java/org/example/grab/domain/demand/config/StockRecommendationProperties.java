package org.example.grab.domain.demand.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

// 생성 단계 재고 추천의 설정값(DEMAND.md 5.3). 근거와 보정 방법은 명세에 둔다
@ConfigurationProperties("grab.demand.stock")
public record StockRecommendationProperties(
        @DefaultValue("0.3") BigDecimal priceBandRatio,
        @DefaultValue("0.5") BigDecimal soldOutThreshold,
        @DefaultValue("20") int maxSampleDrops,
        @DefaultValue("60") int priceReferenceSampleDrops
) {
    public StockRecommendationProperties {
        if (priceBandRatio.signum() <= 0 || priceBandRatio.compareTo(BigDecimal.ONE) >= 0
                || soldOutThreshold.signum() <= 0 || soldOutThreshold.compareTo(BigDecimal.ONE) > 0
                || maxSampleDrops < 1 || priceReferenceSampleDrops < 1) {
            throw new IllegalArgumentException("grab.demand.stock 설정값이 허용 범위를 벗어났습니다.");
        }
    }
}
