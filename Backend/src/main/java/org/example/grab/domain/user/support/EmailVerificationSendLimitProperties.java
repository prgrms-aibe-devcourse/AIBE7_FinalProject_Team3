package org.example.grab.domain.user.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/*
    이메일 인증 코드 발송 제한 설정(GR-61 M02-02, MEMBER_AUTH 1.2 "발송 한도 값은 서버 설정으로 관리한다").
    값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    - resendInterval: 같은 이메일은 이 시간이 지나야 다시 요청할 수 있다
    - emailMaxRequests / emailWindow: 같은 이메일은 구간마다 이 횟수까지 요청할 수 있다
    - ipMaxRequests / ipWindow: 같은 IP는 구간마다 이 횟수까지 요청할 수 있다
    구간은 첫 요청부터 시작하는 고정 구간이다. 구간이 끝날 때와 다음 구간이 시작할 때 몰아 보내면 짧은 시간에 한도의 두 배까지 요청할 수 있지만,
    재발송 간격이 이메일별로 따로 걸리므로 받아들인다.
 */
@ConfigurationProperties(prefix = "grab.auth.email-verification.send-limit")
public record EmailVerificationSendLimitProperties(
        Duration resendInterval,
        int emailMaxRequests,
        Duration emailWindow,
        int ipMaxRequests,
        Duration ipWindow
) {

    public EmailVerificationSendLimitProperties(Duration resendInterval, int emailMaxRequests, Duration emailWindow,
                                                int ipMaxRequests, Duration ipWindow) {
        requirePositive(resendInterval, "resend-interval");
        requirePositive(emailMaxRequests, "email-max-requests");
        requirePositive(emailWindow, "email-window");
        requirePositive(ipMaxRequests, "ip-max-requests");
        requirePositive(ipWindow, "ip-window");
        this.resendInterval = resendInterval;
        this.emailMaxRequests = emailMaxRequests;
        this.emailWindow = emailWindow;
        this.ipMaxRequests = ipMaxRequests;
        this.ipWindow = ipWindow;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("grab.auth.email-verification.send-limit." + name + " 값은 0보다 커야 합니다.");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("grab.auth.email-verification.send-limit." + name + " 값은 0보다 커야 합니다.");
        }
    }
}
