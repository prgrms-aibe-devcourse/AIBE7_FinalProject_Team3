package org.example.grab.global.security.handler;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/*
    Security 필터 단계의 오류를 GlobalExceptionHandler와 같은 공통 오류 형식으로 쓴다.
    필터에서 막힌 요청은 컨트롤러에 도달하지 않아 @RestControllerAdvice를 거치지 않는다(M03-07).
    메시지는 ErrorCode의 고정 문구만 쓴다. 예외 메시지에는 토큰 정보가 섞일 수 있다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), null));
    }
}
