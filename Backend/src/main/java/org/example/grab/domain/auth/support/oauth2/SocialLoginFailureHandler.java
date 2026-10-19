package org.example.grab.domain.auth.support.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

/*
    제공자 인증 단계의 실패를 프론트엔드 콜백 주소로 보낸다(MEMBER_AUTH 1.5, GR-46 M00-07).
    사용자 취소(access_denied), state 불일치·인가 요청 없음, 미지원 제공자 콜백, 인가 코드·토큰 교환 실패, ID 토큰·nonce 검증 실패,
    KAKAO userinfo 조회 실패·sub 불일치(invalid_user_info_response)는 모두 OAUTH2_AUTHENTICATION_FAILED다. 인증 처리 중 서버 오류(InternalAuthenticationServiceException)는 INTERNAL_SERVER_ERROR다.
    로그에는 OAuth2 오류 코드 종류만 남긴다. 제공자가 보낸 설명 원문과 인가 코드는 남기지 않는다.
    인가 요청을 담았던 세션은 무효화한다(M00-03). 쿠키는 발급하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    // 요청으로 들어온 error 값이 그대로 로그에 남지 않도록 OAuth2 오류 코드 형식(영문, 숫자)만 기록한다
    // 로그 오염을 줄이는 방어 코드
    private static final Pattern ERROR_CODE_FORMAT = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private final SocialLoginRedirects redirects;

    // 사용자 취소, state 불일치, 토큰 교환 실패, ID Token 검증 실패 등이 발생했을 때 호출되는 메서드
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 임시 세션 비우기
            session.invalidate();
        }

        // InternalAuthenticationServiceException일 경우 서버 내부 인증 처리 오류로 분류
        if (exception instanceof InternalAuthenticationServiceException) {
            log.error("소셜 로그인 인증 처리 실패: exception={}", exception.getClass().getSimpleName());
            response.sendRedirect(redirects.error(CommonErrorCode.INTERNAL_SERVER_ERROR.getCode()));
            return;
        }

        log.info("소셜 로그인 실패: reason={}", reasonOf(exception));
        // 일반 인증 실패 시
        response.sendRedirect(redirects.error(UserErrorCode.OAUTH2_AUTHENTICATION_FAILED.getCode()));
    }

    // 로그에 남길 실패 사유 작성
    private static String reasonOf(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth2) || oauth2.getError() == null) {
            return exception.getClass().getSimpleName();
        }
        String code = oauth2.getError().getErrorCode();
        if (code == null || !ERROR_CODE_FORMAT.matcher(code).matches()) {
            return "invalid_error_code";
        }
        return code;
    }
}
