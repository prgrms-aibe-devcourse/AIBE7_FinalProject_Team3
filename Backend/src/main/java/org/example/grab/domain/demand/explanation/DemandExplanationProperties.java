package org.example.grab.domain.demand.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/*
    수요 분석 설명 생성(DEMAND.md 3절). 모델 선택·API 키는 spring.ai.* 설정이 맡고, 여기에는 호출 정책만 둔다.
    thinkingBudget 0은 gemini-2.5-flash의 추론 단계를 꺼 응답 시간과 토큰을 줄인다. 추론을 끌 수 없는 모델로 바꾸면 비워 둔다.
    dailyLimit은 판매자별 하루(KST) LLM 호출 수다. 저장된 설명을 다시 쓴 요청은 세지 않는다(3.1).
 */
@ConfigurationProperties("grab.demand.explanation")
public record DemandExplanationProperties(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("0") Integer thinkingBudget,
        @DefaultValue("30") int dailyLimit
) {
    public DemandExplanationProperties {
        if (timeout.isNegative() || timeout.isZero() || maxOutputTokens < 1 || dailyLimit < 1) {
            throw new IllegalArgumentException(
                    "grab.demand.explanation의 timeout·max-output-tokens·daily-limit은 0보다 커야 합니다.");
        }
    }
}
