package org.example.grab.domain.auth.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/*
    Refresh Token 유효기간 설정(GR-33 M00-01). 값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    - idleTtl: 이 기간 동안 재발급이 없으면 만료된다(비활동 만료). 재발급할 때마다 다시 시작된다
    - absoluteTtl: 로그인 후 이 기간이 지나면 재발급해도 만료된다(절대 만료)
    저장 TTL은 min(idleTtl, 절대 만료까지 남은 시간)이므로 idleTtl이 absoluteTtl보다 길면 비활동 만료가 한 번도 쓰이지 않는다.
    두 값을 바꿔 적는 실수를 기동 시점에 막는다. 두 값이 같으면 비활동 만료 없이 고정 기간으로 동작하며 허용한다.
 */
@ConfigurationProperties(prefix = "grab.auth.refresh-token")
public record RefreshTokenProperties(
        Duration idleTtl,
        Duration absoluteTtl
) {

    public RefreshTokenProperties(Duration idleTtl, Duration absoluteTtl) {
        requirePositive(idleTtl, "idle-ttl");
        requirePositive(absoluteTtl, "absolute-ttl");
        // idleTtl이 absoluteTtl 보다 크면 에러를 던짐
        if (idleTtl.compareTo(absoluteTtl) > 0) {
            throw new IllegalArgumentException("grab.auth.refresh-token.idle-ttl(" + idleTtl
                    + ")은 absolute-ttl(" + absoluteTtl + ")보다 길 수 없습니다.");
        }
        this.idleTtl = idleTtl;
        this.absoluteTtl = absoluteTtl;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("grab.auth.refresh-token." + name + " 값은 0보다 커야 합니다.");
        }
    }
}
