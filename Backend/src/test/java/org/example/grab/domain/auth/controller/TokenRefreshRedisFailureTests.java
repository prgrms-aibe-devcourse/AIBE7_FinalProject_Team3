package org.example.grab.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-35 M02-03(GR-33 M03-03): 재발급 중 Redis 장애는 401이 아니라 500 INTERNAL_SERVER_ERROR여야 한다.
    401(INVALID_TOKEN)이면 프론트엔드가 로그인 화면으로 보내므로 Redis 장애 동안 재발급한 사용자가 전부 로그아웃된다.
    저장소를 mock으로 바꿔 장애를 만든다. 실제 Redis가 필요 없어 컨테이너를 띄우지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TokenRefreshRedisFailureTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("Redis 장애면 500 INTERNAL_SERVER_ERROR이고 인증 쿠키를 내리지 않는다")
    void respondsWithInternalServerErrorOnRedisFailure() throws Exception {
        // given
        given(refreshTokenRepository.find(anyString())).willThrow(new RedisConnectionFailureException("Redis 연결 실패"));
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();

        // when
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(csrf, new Cookie("refresh_token", "some-token"))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn().getResponse();

        // then
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.startsWith("access_token=") || value.startsWith("refresh_token="));
        assertThat(response.getContentAsString()).doesNotContain("Redis 연결 실패");
    }
}
