package org.example.grab;

import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// M03-06: SecurityConfig가 세션·폼 로그인 없이 JWT 필터 하나로 인증하는지 실제 필터 체인으로 확인한다
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterChainTests {

    // 인증되면 보안을 통과해 매핑 없는 경로의 404까지 간다. 막히면 401이다
    private static final String UNMAPPED_PROTECTED_PATH = "/api/v1/security-filter-chain-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("유효한 access_token 쿠키는 보호 경로의 인증을 통과한다")
    void authenticatesWithValidCookie() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("변조된 access_token 쿠키는 401")
    void rejectsInvalidCookie() throws Exception {
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", "not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 실패 응답에 세션 쿠키를 만들지 않는다")
    void doesNotCreateSession() throws Exception {
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("폼 로그인·Basic 인증이 없어 /login 페이지와 Basic 헤더는 인증 수단이 아니다")
    void disablesFormLoginAndHttpBasic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    // TODO(GR-44): CSRF를 다시 켜면 이 테스트는 403을 기대하도록 바꾼다
    @Test
    @DisplayName("CSRF 임시 해제: 토큰 없는 비로그인 PUT은 403이 아니라 401")
    void csrfTemporarilyDisabled() throws Exception {
        mockMvc.perform(put("/api/v1/drops/1/wish"))
                .andExpect(status().isUnauthorized());
    }
}
