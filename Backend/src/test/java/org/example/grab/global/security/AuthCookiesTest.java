package org.example.grab.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-34 M02-01: 인증 쿠키의 이름·경로·속성·Max-Age(COMMON 1.2). 쿠키 이름은 상수 대신 명세 문자열로 단언한다(GR-44 M01-04)
class AuthCookiesTest {

    private static final String TOKEN = "token-value";

    @Test
    @DisplayName("access_token은 HttpOnly·Secure·SameSite=Lax·Path=/api이고 Max-Age는 넘긴 값(초 미만 버림)이다")
    void buildsAccessTokenCookie() {
        // when
        ResponseCookie cookie = AuthCookies.accessToken(TOKEN, Duration.ofSeconds(899).plusMillis(211));

        // then
        assertThat(cookie.getName()).isEqualTo("access_token");
        assertThat(cookie.getValue()).isEqualTo(TOKEN);
        assertThat(cookie.getPath()).isEqualTo("/api");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.toString()).contains("Max-Age=899;");
    }

    @Test
    @DisplayName("refresh_token은 HttpOnly·Secure·SameSite=Lax·Path=/api/v1/auth이고 Max-Age는 넘긴 TTL이다")
    void buildsRefreshTokenCookie() {
        // when
        ResponseCookie cookie = AuthCookies.refreshToken(TOKEN, Duration.ofDays(14));

        // then
        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("토큰 값이 비어 있으면 쿠키를 만들지 않는다")
    void rejectsBlankToken(String token) {
        assertThatThrownBy(() -> AuthCookies.accessToken(token, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthCookies.refreshToken(token, Duration.ofDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Max-Age가 1초 미만(0은 삭제, 음수는 세션 쿠키가 된다)이면 쿠키를 만들지 않고 메시지에 토큰을 넣지 않는다")
    void rejectsMaxAgeUnderOneSecond() {
        assertThatThrownBy(() -> AuthCookies.accessToken(TOKEN, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain(TOKEN);
        assertThatThrownBy(() -> AuthCookies.accessToken(TOKEN, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthCookies.refreshToken(TOKEN, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthCookies.refreshToken(TOKEN, null))
                .isInstanceOf(NullPointerException.class);
    }
}
