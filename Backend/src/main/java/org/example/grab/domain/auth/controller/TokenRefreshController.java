package org.example.grab.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.dto.internal.TokenRefreshResult;
import org.example.grab.domain.auth.service.TokenRefreshService;
import org.example.grab.global.security.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    토큰 재발급(MEMBER_AUTH 1.7). 로그인 없이 호출하고, CSRF 토큰은 다른 상태 변경 요청과 같이 필요하다(GR-44).
    refresh_token 쿠키는 Path=/api/v1/auth라 이 경로에만 실린다. 새 토큰은 Set-Cookie로만 보내고 본문은 없다(204).
    쿠키가 없을 때도 400이 아니라 INVALID_TOKEN(401)으로 응답하도록 required = false로 받아 서비스가 판단한다(GR-35 M00-06).
    CSRF 토큰은 교체하지 않는다(GR-34 M00-06과 같은 기준).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/refresh")
@Tag(name = "토큰 재발급", description = "Refresh Token Rotation")
public class TokenRefreshController {

    private final TokenRefreshService tokenRefreshService;

    // 쿠키는 브라우저가 자동으로 보내므로 Swagger 입력란에서 숨긴다
    @Operation(summary = "토큰 재발급",
            description = "refresh_token 쿠키로 Access·Refresh Token을 새로 발급한다. 사용한 Refresh Token은 폐기된다")
    @ApiResponse(responseCode = "204", description = "재발급 성공, Set-Cookie로 access_token·refresh_token 전달")
    @ApiResponse(responseCode = "401", description = "INVALID_TOKEN")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED(CSRF 토큰 없음·불일치)")
    @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR(Redis 장애 등. 로그아웃하지 않고 다시 시도한다)")
    @PostMapping
    public ResponseEntity<Void> refresh(
            @Parameter(hidden = true) @CookieValue(name = AuthCookies.REFRESH_TOKEN_NAME, required = false) String refreshToken) {
        TokenRefreshResult result = tokenRefreshService.refresh(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.accessToken(
                        result.accessToken().value(), result.accessToken().maxAge()).toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.refreshToken(
                        result.refreshToken(), result.refreshTokenMaxAge()).toString())
                .build();
    }
}
