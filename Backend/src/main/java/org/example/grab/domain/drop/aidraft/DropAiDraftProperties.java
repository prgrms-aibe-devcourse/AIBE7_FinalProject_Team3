package org.example.grab.domain.drop.aidraft;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/*
    사진 기반 DROP 초안(DROP.md 2.8). 모델·API 키는 spring.ai.* 설정이 맡고 여기에는 호출 정책만 둔다.
    판매자가 버튼을 누르고 기다리는 동기 요청이라 시간 제한을 설명 생성(10초)보다 길게 둔다.
    httpTimeout은 전용 Client의 HTTP 시간 제한으로, timeout보다 짧아야 시간 제한을 넘긴 요청이 작업자를 붙잡지 않는다.
 */
@ConfigurationProperties("grab.drop.ai-draft")
public record DropAiDraftProperties(
        @DefaultValue("20s") Duration timeout,
        @DefaultValue("19s") Duration httpTimeout,
        @DefaultValue("5s") Duration imageDownloadTimeout,
        @DefaultValue("20") int dailyLimit,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("0") Integer thinkingBudget
) {
    public DropAiDraftProperties {
        if (timeout.isNegative() || timeout.isZero() || httpTimeout.isNegative() || httpTimeout.isZero()
                || httpTimeout.compareTo(timeout) >= 0 || imageDownloadTimeout.isNegative()
                || imageDownloadTimeout.isZero() || dailyLimit < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("grab.drop.ai-draft 설정값이 허용 범위를 벗어났습니다.");
        }
    }
}
