package org.example.grab.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.service.LogoutService;
import org.example.grab.global.security.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    로그아웃(MEMBER_AUTH 1.8). Access Token 없이 호출하고, CSRF 토큰은 다른 상태 변경 요청과 같이 필요하다(GR-36 M00-01).
    쿠키가 없어도 같은 204로 응답하도록 required = false로 받아 서비스에 넘긴다.
    서비스가 세션 폐기를 마친 뒤에만 쿠키를 만료시킨다. Redis 오류면 예외가 전파되어 쿠키가 남고 같은 요청으로 다시 시도할 수 있다(M00-03).
    Spring Security의 기본 /logout 처리는 쓰지 않으며 XSRF-TOKEN은 교체·삭제하지 않는다(GR-34 M00-06과 같은 기준).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/logout")
@Tag(name = "로그아웃", description = "Refresh Token 세션 폐기와 인증 쿠키 만료")
public class LogoutController {

    private final LogoutService logoutService;

    // 쿠키는 브라우저가 자동으로 보내므로 Swagger 입력란에서 숨긴다
    @Operation(summary = "로그아웃",
            description = "refresh_token 쿠키의 세션을 폐기하고 두 인증 쿠키를 만료시킨다. 쿠키가 없거나 이미 폐기된 토큰이어도 204다")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공, Set-Cookie로 access_token·refresh_token 만료")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED(CSRF 토큰 없음·불일치)")
    @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR(Redis 장애 등. 쿠키가 유지되므로 다시 시도한다)")
    @PostMapping
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true) @CookieValue(name = AuthCookies.REFRESH_TOKEN_NAME, required = false) String refreshToken) {
        logoutService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.expireAccessToken().toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.expireRefreshToken().toString())
                .build();
    }
}
