package org.example.grab.domain.demand.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 판매자 화면이 수요 분석을 먼저 제안하는 기준(DEMAND.md 1.8)
@ConfigurationProperties("grab.demand.suggestion")
public record DemandSuggestionProperties(
        @DefaultValue("30") int minActiveWishes,
        @DefaultValue("48") int hoursBeforeStart
) {
    public DemandSuggestionProperties {
        if (minActiveWishes < 1 || hoursBeforeStart < 1) {
            throw new IllegalArgumentException("grab.demand.suggestion 설정은 1 이상이어야 합니다.");
        }
    }
}
