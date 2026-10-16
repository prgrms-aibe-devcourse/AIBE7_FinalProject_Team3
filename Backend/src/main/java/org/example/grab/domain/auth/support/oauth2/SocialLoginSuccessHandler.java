package org.example.grab.domain.auth.support.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.dto.internal.SocialLoginOutcome;
import org.example.grab.domain.auth.service.SocialLoginService;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.support.OAuth2SignupTokenCookie;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/*
    제공자 인증(인가 코드·state·ID 토큰·nonce 검증)이 성공한 뒤 콜백 판정을 하고 프론트엔드로 리다이렉트한다(MEMBER_AUTH 1.5, GR-46 M00-05·07).
    - 기존 회원: access_token·refresh_token 쿠키 + result=success
    - 최초 인증: oauth2_signup_token 쿠키 + result=signup_required
    - 판정 거부(OAUTH2_EMAIL_REQUIRED·ACCOUNT_LINK_REQUIRED·ACCOUNT_DISABLED 등): 쿠키 없이 error=<코드>
    - 서버 오류(Redis·DB): 쿠키 없이 error=INTERNAL_SERVER_ERROR. 인증 실패가 아니다(GR-33)
    쿠키는 판정과 저장이 모두 성공한 뒤에만 싣는다. 실패한 응답에는 어떤 쿠키도 없다.
    인가 요청을 담았던 세션은 무효화하고, 로그인 상태는 세션이 아니라 인증 쿠키로만 판단한다(M00-03).
    로그에는 제공자 이름·결과 코드·내부 회원 ID만 남긴다. 토큰·이메일·제공자 식별자는 URL·로그에 남기지 않는다(NFR-010).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final SocialLoginService socialLoginService;
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
        // OAuth2AuthenticationToken(Spring의 OAuth2 인증 결과 객체)이면 등록 id인 kakao 또는 google을 가져옴
        // 아니면 제공자를 알 수 없으므로 인증 실패로 보냄
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            log.warn("소셜 로그인 거부: provider=unknown, code={}", UserErrorCode.OAUTH2_AUTHENTICATION_FAILED.getCode());
            response.sendRedirect(redirects.error(UserErrorCode.OAUTH2_AUTHENTICATION_FAILED.getCode()));
            return;
        }
        String provider = token.getAuthorizedClientRegistrationId();

        try {
            // 사용자 정보를 추출 후 GRAB 회원을 판정
            SocialLoginOutcome outcome = socialLoginService.login(SocialProfile.from(token));
            // 로그인 결과에서 신규 가입이 필요하다면 브라우저에 가입 컨텍스트 넣고 가입 경로로 리다이렉트
            if (outcome.isSignupRequired()) {
                response.addHeader(HttpHeaders.SET_COOKIE, OAuth2SignupTokenCookie.issue(outcome.signupToken()).toString());
                log.info("소셜 로그인 가입 필요: provider={}", provider);
                response.sendRedirect(redirects.signupRequired());
                return;
            }

            // GRAB 회원 조회가 완료되었다면 Access token, Refresh token 원문과 max-age 브라우저 헤더에..
            LoginResult result = outcome.loginResult();
            response.addHeader(HttpHeaders.SET_COOKIE,
                    AuthCookies.accessToken(result.accessToken().value(), result.accessToken().maxAge()).toString());
            response.addHeader(HttpHeaders.SET_COOKIE,
                    AuthCookies.refreshToken(result.refreshToken(), result.refreshTokenMaxAge()).toString());
            log.info("소셜 로그인 성공: provider={}, userId={}", provider, result.user().publicId());
            response.sendRedirect(redirects.success());
        } catch (BusinessException e) {
            String code = e.getErrorCode().getCode();
            log.info("소셜 로그인 거부: provider={}, code={}", provider, code);
            response.sendRedirect(redirects.error(code));
        } catch (RuntimeException e) {
            // 예외 메시지에 SQL·입력값이 담길 수 있어 종류만 남긴다
            log.error("소셜 로그인 처리 실패: provider={}, exception={}", provider, e.getClass().getSimpleName());
            response.sendRedirect(redirects.error(CommonErrorCode.INTERNAL_SERVER_ERROR.getCode()));
        }
    }
}
