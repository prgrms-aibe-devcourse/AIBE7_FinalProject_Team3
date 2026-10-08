package org.example.grab.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    CSRF 토큰 발급(MEMBER_AUTH 1.1). 프론트엔드는 상태 변경 요청 전에 호출해 XSRF-TOKEN 쿠키를 받고,
    같은 값을 X-XSRF-TOKEN 헤더로 보낸다(COMMON 1.3). 쿠키 속성은 SecurityConfig의 CSRF 저장소가 정한다.
 */
@RestController
@RequestMapping("/api/v1/auth/csrf")
@Tag(name = "CSRF", description = "상태 변경 요청에 쓸 CSRF 토큰 발급")
public class CsrfController {

    /*
        CSRF 토큰은 필요할 때 만들어지는 지연 토큰이다. getToken()으로 값을 읽어야 토큰이 만들어지고 쿠키가 응답에 실린다.
        요청에 이미 유효한 XSRF-TOKEN 쿠키가 있으면 그 토큰을 그대로 쓰므로 Set-Cookie가 없을 수 있다.
     */
    @Operation(summary = "CSRF 토큰 발급",
            description = "XSRF-TOKEN 쿠키를 발급한다. POST·PUT·PATCH·DELETE 요청 전에 먼저 호출한다(Swagger UI의 Try it out 포함)")
    @ApiResponse(responseCode = "204", description = "발급 완료, Set-Cookie로 XSRF-TOKEN 전달")
    @GetMapping
    public ResponseEntity<Void> issueCsrfToken(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }
}
