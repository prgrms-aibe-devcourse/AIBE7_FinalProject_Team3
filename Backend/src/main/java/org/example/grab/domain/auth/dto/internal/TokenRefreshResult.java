package org.example.grab.domain.auth.dto.internal;

import org.example.grab.global.security.jwt.IssuedAccessToken;

import java.time.Duration;

/**
 * 토큰 재발급 결과(GR-35). Controller가 두 인증 쿠키를 만드는 데만 쓴다. 응답 본문은 없다(MEMBER_AUTH 1.7).
 *
 * @param accessToken        새 Access Token, 만료 시각(exp), access_token 쿠키 Max-Age
 * @param refreshToken       새 Refresh Token 원문. 서버에는 해시만 저장돼 있다
 * @param refreshTokenMaxAge Redis 저장 TTL. refresh_token 쿠키 Max-Age
 */
public record TokenRefreshResult(
        IssuedAccessToken accessToken,
        String refreshToken,
        Duration refreshTokenMaxAge
) {

    // record의 기본 toString()은 Refresh Token 원문을 출력하므로 가린다(LoginResult와 같음)
    @Override
    public String toString() {
        return "TokenRefreshResult[accessToken=" + accessToken
                + ", refreshToken=masked, refreshTokenMaxAge=" + refreshTokenMaxAge + "]";
    }
}
