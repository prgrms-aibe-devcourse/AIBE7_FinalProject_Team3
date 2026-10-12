package org.example.grab.global.security.jwt;

import java.time.Duration;
import java.time.Instant;

/*
    발급한 Access Token과 만료 정보(GR-34).
    - expiresAt: JWT exp와 같은 시각
    - maxAge: 발급 시점부터 exp까지 남은 시간. access_token 쿠키 Max-Age로 써서 쿠키가 exp보다 늦게 남지 않게 한다.
      발급과 같은 시계로 계산해야 하므로 JwtProvider가 함께 돌려준다(로그인·재발급이 시계를 따로 갖지 않는다)
 */
public record IssuedAccessToken(String value, Instant expiresAt, Duration maxAge) {

    /*
        record가 자동으로 만드는 toString()은 토큰 원문을 출력해 로그·예외 메시지·디버거에 남는다.
        토큰은 탈취되면 만료 전까지 그대로 쓸 수 있으므로 항상 가린다.
     */
    @Override
    public String toString() {
        return "IssuedAccessToken[value=masked, expiresAt=" + expiresAt + ", maxAge=" + maxAge + "]";
    }
}
