package org.example.grab.domain.user.support;

import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.springframework.http.ResponseCookie;

/*
    가입 컨텍스트 토큰 쿠키(MEMBER_AUTH 1.2.2). 이름·경로는 회원가입 완료(GR-29)에서 쿠키를 읽고 지울 때도 같은 값을 쓴다.
    - HttpOnly: JavaScript에서 읽을 수 없게 해 XSS로 토큰이 새지 않게 한다
    - Secure: HTTPS로만 보낸다. 브라우저는 http://localhost도 안전한 출처로 보므로 로컬 개발에서도 동작한다
    - SameSite=Lax: 다른 사이트에서 보낸 POST에는 쿠키를 붙이지 않는다
    - Path=/api/v1/auth/signup: 회원가입 완료 요청에만 쿠키가 실린다
    - Max-Age: 서버 컨텍스트 TTL(15분)과 같아 쿠키와 컨텍스트가 같은 시각에 만료된다(M00-03)
 */
public final class EmailSignupTokenCookie {

    public static final String NAME = "email_signup_token";
    public static final String PATH = "/api/v1/auth/signup";

    private EmailSignupTokenCookie() {
    }

    public static ResponseCookie issue(String rawToken) {
        return ResponseCookie.from(NAME, rawToken) // 쿠키 이름이 email_signup_token
                .httpOnly(true) //Js에서 쿠키를 읽지 못하도록 설정
                .secure(true) // HTTPS 연결에서 전송하도록 설정
                .sameSite("Lax") // 다른 사이트에서 시작한 POST 요청에는 쿠키 전송 제한
                .path(PATH) // /api/v1/auth/signup과 그 하위 경로에 쿠키 전송하도록 제한
                .maxAge(EmailSignupContextRepository.CONTEXT_TTL) // 15분 설정
                .build();
    }
}
