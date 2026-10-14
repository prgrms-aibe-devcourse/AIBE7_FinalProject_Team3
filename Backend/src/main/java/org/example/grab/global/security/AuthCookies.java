package org.example.grab.global.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Objects;

/*
    인증 쿠키의 이름·경로·속성(COMMON 1.2). 쿠키를 읽는 쪽(JwtAuthenticationFilter)과 발급하는 쪽(로그인 GR-34, 재발급 GR-35)이 같은 값을 쓴다.
    - access_token: Path=/api, 모든 API 요청에 실린다
    - refresh_token: Path=/api/v1/auth, 재발급·로그아웃 요청에만 실린다
    - HttpOnly: JavaScript에서 읽을 수 없게 해 XSS로 토큰이 새지 않게 한다
    - Secure: HTTPS로만 보낸다. 브라우저는 http://localhost도 안전한 출처로 보므로 로컬 개발에서도 동작한다(환경별 분기 없음, GR-44 M00-05)
    - SameSite=Lax: 다른 사이트에서 보낸 POST에는 쿠키를 붙이지 않는다
    발급과 삭제(로그아웃 GR-36)가 같은 속성을 쓰도록 baseCookie 하나에서 만든다.
 */
public final class AuthCookies {

    public static final String ACCESS_TOKEN_NAME = "access_token";
    public static final String ACCESS_TOKEN_PATH = "/api";
    public static final String REFRESH_TOKEN_NAME = "refresh_token";
    public static final String REFRESH_TOKEN_PATH = "/api/v1/auth";

    private AuthCookies() {
    }

    // maxAge는 JWT exp까지 남은 시간이다. 쿠키가 exp보다 늦게 남지 않도록 호출하는 쪽에서 계산한다(GR-34 M01-03)
    public static ResponseCookie accessToken(String token, Duration maxAge) {
        return issue(ACCESS_TOKEN_NAME, ACCESS_TOKEN_PATH, token, maxAge);
    }

    // maxAge는 RefreshTokenRepository.save·rotate가 돌려준 Redis 저장 TTL이다. 쿠키와 서버 세션이 같은 시각에 만료된다
    public static ResponseCookie refreshToken(String token, Duration maxAge) {
        return issue(REFRESH_TOKEN_NAME, REFRESH_TOKEN_PATH, token, maxAge);
    }

    /*
        로그아웃 시 브라우저의 쿠키를 지운다(MEMBER_AUTH 1.8, GR-36 M01-01). 서버는 쿠키를 직접 지울 수 없어 Max-Age=0 속성으로 새로운 쿠키를 보낸다.
        브라우저는 이름·Path가 같아야 같은 쿠키로 보므로 발급과 같은 속성을 쓴다. 두 쿠키는 Path가 달라 하나로 함께 지울 수 없다.
        발급용 issue()의 빈 값·1초 미만 Max-Age 검증을 거치지 않도록 따로 만든다.
     */
    public static ResponseCookie expireAccessToken() {
        return expire(ACCESS_TOKEN_NAME, ACCESS_TOKEN_PATH);
    }

    public static ResponseCookie expireRefreshToken() {
        return expire(REFRESH_TOKEN_NAME, REFRESH_TOKEN_PATH);
    }

    /*
        ResponseCookie는 음수 Max-Age를 "속성 없음"으로 내보내 브라우저를 닫을 때까지 남는 세션 쿠키가 되고, 0이면 쿠키를 지운다.
        둘 다 발급으로는 잘못된 값이므로 1초 미만이면 예외를 던진다. Max-Age는 초 단위라 1초 미만은 0으로 내려간다.
        예외 메시지에는 토큰 값을 넣지 않는다.
     */
    private static ResponseCookie issue(String name, String path, String token, Duration maxAge) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(name + " 쿠키 값은 비어 있을 수 없습니다.");
        }
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.getSeconds() < 1) {
            throw new IllegalArgumentException(name + " 쿠키의 Max-Age는 1초 이상이어야 합니다. 현재 " + maxAge);
        }
        return baseCookie(name, path, token)
                .maxAge(maxAge)
                .build();
    }

    private static ResponseCookie expire(String name, String path) {
        return baseCookie(name, path, "")
                .maxAge(0)
                .build();
    }

    private static ResponseCookie.ResponseCookieBuilder baseCookie(String name, String path, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(path);
    }
}
