package org.example.grab.domain.demand.option;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/*
    옵션별 재고 배분의 LLM 매칭 정책(DEMAND.md 7.2·7.3·7.4). 모델·API 키는 spring.ai.* 설정이 맡는다.
    timeout은 공용 Gemini 연결의 HTTP 시간 제한(9초)보다 길어야 한다.
 */
@ConfigurationProperties("grab.demand.option-match")
public record OptionMatchProperties(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("30") int dailyLimit,
        @DefaultValue("100") int maxCandidates
) {
    public OptionMatchProperties {
        if (timeout.isNegative() || timeout.isZero() || dailyLimit < 1 || maxCandidates < 1) {
            throw new IllegalArgumentException("grab.demand.option-match 설정값이 허용 범위를 벗어났습니다.");
        }
    }
}
