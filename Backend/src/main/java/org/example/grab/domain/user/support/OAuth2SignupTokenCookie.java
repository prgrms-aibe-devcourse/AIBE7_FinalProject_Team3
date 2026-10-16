package org.example.grab.domain.user.support;

import org.example.grab.domain.user.repository.OAuth2SignupContextRepository;
import org.springframework.http.ResponseCookie;

/*
    소셜 가입 컨텍스트 토큰 쿠키(MEMBER_AUTH 1.5·1.6, GR-46 M00-06). 콜백에서 발급하고 가입 완료에서 읽고 지운다.
    - HttpOnly·Secure·SameSite=Lax: email_signup_token과 같은 이유
    - Path=/api/v1/auth/oauth2/signup: 소셜 가입 완료 요청에만 실린다. LOCAL 가입(/api/v1/auth/signup)에는 실리지 않는다
    - Max-Age: 서버 컨텍스트 TTL(15분)과 같아 쿠키와 컨텍스트가 같은 시각에 만료된다
 */
public final class OAuth2SignupTokenCookie {

    public static final String NAME = "oauth2_signup_token";
    public static final String PATH = "/api/v1/auth/oauth2/signup";

    private OAuth2SignupTokenCookie() {
    }

    public static ResponseCookie issue(String rawToken) {
        return baseCookie(rawToken)
                .maxAge(OAuth2SignupContextRepository.CONTEXT_TTL)
                .build();
    }

    // 가입 성공 시 브라우저의 쿠키를 지운다. 이름·Path가 같아야 같은 쿠키로 보므로 발급과 같은 속성을 쓴다
    public static ResponseCookie expire() {
        return baseCookie("")
                .maxAge(0)
                .build();
    }

    private static ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(PATH);
    }
}
