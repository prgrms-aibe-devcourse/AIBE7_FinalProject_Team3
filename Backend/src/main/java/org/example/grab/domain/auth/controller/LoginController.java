package org.example.grab.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.dto.request.LoginRequest;
import org.example.grab.domain.auth.dto.response.LoginResponse;
import org.example.grab.domain.auth.service.LoginService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    LOCAL 로그인(MEMBER_AUTH 1.3). 로그인 없이 호출하고, CSRF 토큰은 다른 상태 변경 요청과 같이 필요하다(GR-44).
    토큰은 Set-Cookie로만 보내고 본문에는 회원 정보만 담는다.
    CSRF 토큰은 로그인 성공 후에도 교체하지 않는다(GR-34 M00-06). 위조 요청은 SameSite=Lax·CORS·JSON 본문 요구가 막고,
    로그인 후 토큰 탈취는 XSS 문제라 교체로 막을 수 없다. 쿠키 구성(Domain·SameSite)이 바뀌면 다시 검토한다.
    응답 본문의 ApiResponse와 이름이 겹쳐 Swagger의 @ApiResponse는 전체 이름으로 쓴다.
    Swagger에는 요약과 오류 코드만 적는다. 세부 규칙은 명세(MEMBER_AUTH.md)를 기준으로 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/login")
@Tag(name = "로그인", description = "LOCAL 이메일·비밀번호 로그인")
public class LoginController {

    private final LoginService loginService;

    @Operation(summary = "로그인",
            description = "이메일·비밀번호로 로그인한다. Access·Refresh Token은 HttpOnly 쿠키로만 전달한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공, Set-Cookie로 access_token·refresh_token 전달")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCOUNT_DISABLED, ACCESS_DENIED(CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "AUTH_TEMPORARILY_UNAVAILABLE(비밀번호 해시 동시 처리 상한 초과, 쿠키 미발급)",
            headers = @Header(name = "Retry-After", description = "재시도 대기 초", schema = @Schema(type = "integer", format = "int64")))
    @PostMapping
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request.email(), request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.accessToken(
                        result.accessToken().value(), result.accessToken().maxAge()).toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.refreshToken(
                        result.refreshToken(), result.refreshTokenMaxAge()).toString())
                .body(ApiResponse.success(LoginResponse.from(result)));
    }
}
