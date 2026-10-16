package org.example.grab.domain.auth.support.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/*
    제공자 인증(인가 코드·state·ID 토큰·nonce 검증)이 성공한 뒤 호출된다(MEMBER_AUTH 1.5).
    인가 요청을 담았던 세션은 무효화하고, 로그인 상태는 세션이 아니라 인증 쿠키로만 판단한다(GR-46 M00-03).
    콜백 판정(기존 회원 로그인·가입 필요·충돌·비활성)과 쿠키 발급은 M01-05에서 채운다.
    그 전까지는 회원을 만들거나 로그인시키지 않고 error=INTERNAL_SERVER_ERROR로 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final SocialLoginRedirects redirects;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // 로그인 왕복에 사용되었던 임시 세션을 정리(state, nonce, pkce 등)
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        // 현재 요청의 인증 컨텍스트를 비움 -> GRAB에서는 이후 자체 인증 쿠키로 로그인 상태를 확인하기 때문
        SecurityContextHolder.clearContext();
        // authentication이 OAuth2AuthenticationToken이면 등록 id인 kakao 또는 google을 가져옴
        // 아니면 unknown, OAuth2AuthenticationToken는 Spring의 인증 결과 객체
        String provider = authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId() : "unknown";

        // 추후 회원 확인과 자체 토큰 발급 연결 예정
        log.warn("소셜 로그인 콜백 판정 미구현: provider={}", provider);
        response.sendRedirect(redirects.error(CommonErrorCode.INTERNAL_SERVER_ERROR.getCode()));
    }
}
