package org.example.grab.global.security;

/*
    인증 쿠키의 이름·경로(COMMON 1.2). 쿠키를 읽는 쪽(JwtAuthenticationFilter)과 발급하는 쪽(로그인·재발급, GR-34)이 같은 값을 쓴다.
    - access_token: Path=/api, 모든 API 요청에 실린다
    - refresh_token: Path=/api/v1/auth, 재발급·로그아웃 요청에만 실린다
    HttpOnly·Secure·SameSite=Lax 속성과 발급·삭제 메서드는 사용처가 생기는 GR-34에서 이 클래스에 추가한다(GR-44 M00-05).
 */
public final class AuthCookies {

    public static final String ACCESS_TOKEN_NAME = "access_token";
    public static final String ACCESS_TOKEN_PATH = "/api";
    public static final String REFRESH_TOKEN_NAME = "refresh_token";
    public static final String REFRESH_TOKEN_PATH = "/api/v1/auth";

    private AuthCookies() {
    }
}
