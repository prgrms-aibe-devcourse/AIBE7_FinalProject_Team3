package org.example.grab.global.security.jwt;

import java.time.Instant;

/*
    발급한 Access Token과 만료 시각. 쿠키 Max-Age를 JWT exp와 맞추도록 만료 시각을 함께 돌려준다(GR-34).
 */
public record IssuedAccessToken(String value, Instant expiresAt) {

    /*
        record가 자동으로 만드는 toString()은 토큰 원문을 출력해 로그·예외 메시지·디버거에 남는다.
        토큰은 탈취되면 만료 전까지 그대로 쓸 수 있으므로 항상 가린다.
     */
    @Override
    public String toString() {
        return "IssuedAccessToken[value=masked, expiresAt=" + expiresAt + "]";
    }
}
