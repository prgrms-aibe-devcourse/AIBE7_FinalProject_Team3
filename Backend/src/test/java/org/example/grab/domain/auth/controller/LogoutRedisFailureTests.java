package org.example.grab.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-36 M02-04(M00-03): 로그아웃 중 Redis 장애는 204가 아니라 500 INTERNAL_SERVER_ERROR여야 한다.
    폐기를 확인하지 못했는데 쿠키를 지우면 서버에 토큰이 남은 채 같은 요청으로 다시 시도할 수 없다.
    저장소를 mock으로 바꿔 조회·세션 폐기·표식 기반 폐기 각각에서 장애를 만든다. 실제 Redis가 필요 없어 컨테이너를 띄우지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LogoutRedisFailureTests {

    private static final String TOKEN = "some-token";
    private static final String FAILURE_MESSAGE = "Redis 연결 실패";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    // 500·공통 오류 형식, 인증 쿠키 만료 없음, 내부 예외 메시지·토큰 미노출
    private void assertInternalServerErrorWithoutExpiringCookies() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();

        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(csrf, new Cookie("refresh_token", TOKEN))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn().getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.startsWith("access_token=") || value.startsWith("refresh_token="));
        assertThat(response.getContentAsString()).doesNotContain(FAILURE_MESSAGE, TOKEN);
    }

    @Test
    @DisplayName("조회 중 Redis 장애면 500이고 인증 쿠키를 만료시키지 않는다")
    void failsOnFind() throws Exception {
        // given
        given(refreshTokenRepository.find(anyString())).willThrow(new RedisConnectionFailureException(FAILURE_MESSAGE));

        // when & then
        assertInternalServerErrorWithoutExpiringCookies();
    }

    @Test
    @DisplayName("세션 폐기 중 Redis 장애면 500이고 인증 쿠키를 만료시키지 않는다")
    void failsOnRevokeSession() throws Exception {
        // given
        given(refreshTokenRepository.find(anyString())).willReturn(Optional.of(
                new RefreshTokenSession(7L, UUID.randomUUID(), Instant.now().plusSeconds(3600))));
        willThrow(new RedisConnectionFailureException(FAILURE_MESSAGE))
                .given(refreshTokenRepository).revokeSession(any());

        // when & then
        assertInternalServerErrorWithoutExpiringCookies();
    }

    @Test
    @DisplayName("표식 기반 폐기 중 Redis 장애면 500이고 인증 쿠키를 만료시키지 않는다")
    void failsOnRevokeByUsedToken() throws Exception {
        // given
        given(refreshTokenRepository.find(anyString())).willReturn(Optional.empty());
        given(refreshTokenRepository.revokeSessionByUsedToken(anyString()))
                .willThrow(new RedisConnectionFailureException(FAILURE_MESSAGE));

        // when & then
        assertInternalServerErrorWithoutExpiringCookies();
    }
}
