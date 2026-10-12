package org.example.grab.domain.auth.dto.internal;

import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;

import java.time.Duration;
import java.util.Set;

/**
 * 로그인 결과. Controller가 응답 본문(user, roles)과 인증 쿠키(토큰, Max-Age)를 만드는 데 쓴다.
 * 토큰 원문을 담으므로 응답 본문으로 직렬화하지 않는다.
 *
 * @param user               로그인한 회원. 내부 ID를 담고 있어 응답에는 필요한 필드만 옮긴다
 * @param roles              발급 시점의 유효 권한. JWT roles Claim과 같다
 * @param accessToken        Access Token, 만료 시각(exp), access_token 쿠키 Max-Age
 * @param refreshToken       Refresh Token 원문. 서버에는 해시만 저장돼 있다
 * @param refreshTokenMaxAge Redis 저장 TTL. refresh_token 쿠키 Max-Age
 */
public record LoginResult(
        LoginUser user,
        Set<AuthRole> roles,
        IssuedAccessToken accessToken,
        String refreshToken,
        Duration refreshTokenMaxAge
) {

    /*
        record가 자동으로 만드는 toString()은 Refresh Token 원문을 출력해 로그·예외 메시지·디버거에 남는다(GR-33 인계).
        Access Token은 IssuedAccessToken.toString()이 이미 가린다.
     */
    @Override
    public String toString() {
        return "LoginResult[user=" + user.publicId() + ", roles=" + roles + ", accessToken=" + accessToken
                + ", refreshToken=masked, refreshTokenMaxAge=" + refreshTokenMaxAge + "]";
    }
}
