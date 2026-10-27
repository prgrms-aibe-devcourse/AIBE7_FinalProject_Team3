package org.example.grab.domain.demand.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/*
    수요 분석 설명 생성(DEMAND.md 3절). 모델 선택·API 키는 spring.ai.* 설정이 맡고, 여기에는 호출 정책만 둔다.
    thinkingBudget 0은 gemini-2.5-flash의 추론 단계를 꺼 응답 시간과 토큰을 줄인다. 추론을 끌 수 없는 모델로 바꾸면 비워 둔다.
    dailyLimit은 판매자별 하루(KST) LLM 호출 수다. 저장된 설명을 다시 쓴 요청은 세지 않는다(3.1).
    httpTimeout은 Gemini HTTP 요청 자체의 시간 제한이다. 서비스가 timeout 뒤 결과를 버려도 진행 중인 요청은 멈추지 않으므로,
    timeout보다 짧게 두어 느린 요청이 전용 풀의 작업자를 계속 붙잡지 않게 한다(DemandExplanationConfig).
 */
@ConfigurationProperties("grab.demand.explanation")
public record DemandExplanationProperties(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("9s") Duration httpTimeout,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("0") Integer thinkingBudget,
        @DefaultValue("30") int dailyLimit
) {
    public DemandExplanationProperties {
        if (timeout.isNegative() || timeout.isZero() || maxOutputTokens < 1 || dailyLimit < 1) {
            throw new IllegalArgumentException(
                    "grab.demand.explanation의 timeout·max-output-tokens·daily-limit은 0보다 커야 합니다.");
        }
        if (httpTimeout.isNegative() || httpTimeout.isZero() || httpTimeout.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException(
                    "grab.demand.explanation.http-timeout은 0보다 크고 timeout보다 짧아야 합니다.");
        }
    }
}
