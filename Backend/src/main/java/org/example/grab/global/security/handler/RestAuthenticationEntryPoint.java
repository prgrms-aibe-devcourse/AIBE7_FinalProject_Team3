package org.example.grab.global.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.jwt.InvalidAccessTokenException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/*
    인증이 없거나 실패한 요청의 401 응답(M00-09).
    - JwtAuthenticationFilter가 토큰 검증 실패로 넘긴 경우 → INVALID_TOKEN
    - 쿠키 없이 보호 경로에 접근해 인가 단계에서 넘어온 경우 → AUTHENTICATION_REQUIRED
    거부 사유 로그는 필터가 남기므로 여기서는 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        CommonErrorCode errorCode = authException instanceof InvalidAccessTokenException
                ? CommonErrorCode.INVALID_TOKEN
                : CommonErrorCode.AUTHENTICATION_REQUIRED;
        errorResponseWriter.write(response, errorCode);
    }
}
