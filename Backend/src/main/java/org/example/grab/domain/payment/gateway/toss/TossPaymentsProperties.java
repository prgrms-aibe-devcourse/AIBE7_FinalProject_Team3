package org.example.grab.domain.payment.gateway.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 토스페이먼츠 연동 설정(grab.toss.*).
 *
 * @param secretKey 테스트 시크릿 키(test_sk_). 환경변수 TOSS_SECRET_KEY로만 주입하며 비어 있으면 결제 승인을 시도하지 않는다
 */
@ConfigurationProperties("grab.toss")
public record TossPaymentsProperties(
        @DefaultValue("https://api.tosspayments.com") String baseUrl,
        String secretKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout
) {

    public boolean hasSecretKey() {
        return secretKey != null && !secretKey.isBlank();
    }

    // record 기본 toString은 시크릿 키 원문을 출력하므로 로그·예외 메시지에 새지 않도록 가린다.
    @Override
    public String toString() {
        return "TossPaymentsProperties[baseUrl=" + baseUrl
                + ", secretKey=" + (hasSecretKey() ? "masked" : "empty")
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
