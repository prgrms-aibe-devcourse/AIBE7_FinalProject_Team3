package org.example.grab.domain.auth.support;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/*
    Refresh Token의 절대 만료 시각과 저장 TTL을 계산한다(GR-33 M00-01).
    TTL = min(idleTtl, sessionExpiresAt - now)를 초 단위로 내린 값. 재발급은 세션의 sessionExpiresAt을 이어받으므로
    재발급을 반복해도 로그인 후 absoluteTtl을 넘기지 못한다. 쿠키 Max-Age도 이 TTL을 쓴다.
 */
@Component
public class RefreshTokenTtlCalculator {

    private final RefreshTokenProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenTtlCalculator(RefreshTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RefreshTokenTtlCalculator(RefreshTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // 로그인으로 새 세션을 만들 때만 쓴다. 재발급은 기존 값을 이어받는다
    public Instant newSessionExpiresAt() {
        return clock.instant().plus(properties.absoluteTtl());
    }

    public Duration calculateTtl(Instant sessionExpiresAt) {
        if (sessionExpiresAt == null) {
            throw new IllegalArgumentException("세션 절대 만료 시각은 필수입니다.");
        }

        Duration remaining = Duration.between(clock.instant(), sessionExpiresAt);
        // 남은 만료 시간이 비활동 만료 기간보다 짧을 경우 남은 시간, 아니면 기본 유효 기간
        Duration ttl = remaining.compareTo(properties.idleTtl()) < 0 ? remaining : properties.idleTtl();

        /*
            쿠키 Max-Age는 초 단위라 TTL을 초 단위로 내려 Redis TTL과 같게 하고, 절대 만료보다 늦게 남지 않게 한다(GR-35 M00-05).
            1초 미만이면 쿠키를 만들 수 없으므로(AuthCookies) 저장 전에 거부한다.
            절대 만료가 지난 세션은 재발급 실패와 같으므로 만료와 없음을 구분하지 않고 INVALID_TOKEN으로 응답한다(GR-33 M00-08)
         */
        long seconds = ttl.getSeconds();
        if (seconds < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_TOKEN);
        }
        return Duration.ofSeconds(seconds);
    }
}
