package org.example.grab.domain.payment.gateway.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MockPaymentGateway의 장애 주입 설정(grab.payment.mock-gateway.*, GR-155).
 * 기본값은 전부 0(장애 없음)이라, GR-154 soak처럼 장애 주입이 필요 없는 테스트에는 영향이 없다.
 *
 * @param confirmFailureRate 결제 승인을 거절(NOT_APPROVED)로 떨어뜨릴 확률(0~1)
 * @param confirmUnknownRate 결제 승인 결과를 불명(UNKNOWN)으로 떨어뜨릴 확률(0~1).
 *                           PaymentBatchService의 "진행 중 결제 정리"가 처리해야 한다
 * @param cancelUnknownRate  결제 취소 결과를 불명(UNKNOWN)으로 떨어뜨릴 확률(0~1).
 *                           "결제 취소 재요청" 배치가 처리해야 한다
 * @param resolveDelaySeconds 결과불명으로 떨어진 승인·취소가 몇 초 뒤에 확정되는지. PaymentService의
 *                            즉시 조회(confirm 직후 한 번)보다 길게 둬야 배치가 나중에 다시 물어봐서
 *                            해결하는 경로(진행 중 결제 정리, 취소 재요청)가 실제로 실행된다
 */
@ConfigurationProperties("grab.payment.mock-gateway")
public record MockPaymentGatewayProperties(
        @DefaultValue("0") double confirmFailureRate,
        @DefaultValue("0") double confirmUnknownRate,
        @DefaultValue("0") double cancelUnknownRate,
        @DefaultValue("20") long resolveDelaySeconds
) {
    public MockPaymentGatewayProperties {
        requireRate(confirmFailureRate, "confirmFailureRate");
        requireRate(confirmUnknownRate, "confirmUnknownRate");
        requireRate(cancelUnknownRate, "cancelUnknownRate");
        if (confirmFailureRate + confirmUnknownRate > 1) {
            throw new IllegalArgumentException(
                    "confirmFailureRate + confirmUnknownRate must be <= 1, got " + confirmFailureRate + " + " + confirmUnknownRate);
        }
        if (resolveDelaySeconds < 0) {
            throw new IllegalArgumentException("resolveDelaySeconds must be >= 0, got " + resolveDelaySeconds);
        }
    }

    private static void requireRate(double rate, String name) {
        if (!Double.isFinite(rate) || rate < 0 || rate > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, got " + rate);
        }
    }
}
