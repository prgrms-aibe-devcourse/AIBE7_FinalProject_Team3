package org.example.grab.global.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/*
    인증은 됐지만 Security 인가 규칙에 막힌 요청의 403 응답(M00-09).
    판매자 API의 승인 여부 판단은 CurrentSellerIdProvider가 BusinessException으로 던져
    GlobalExceptionHandler가 응답하므로, 이 핸들러는 SecurityConfig의 URL 권한 규칙에 걸린 경우에만 쓰인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // 쿼리 문자열·헤더·쿠키는 남기지 않는다
        log.warn("접근 거부: {} {}", request.getMethod(), request.getRequestURI());
        errorResponseWriter.write(response, CommonErrorCode.ACCESS_DENIED);
    }
}
