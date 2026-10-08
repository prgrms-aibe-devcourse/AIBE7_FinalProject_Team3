package org.example.grab;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.user.service.EmailVerificationService;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.JwtProvider;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-44 M02-02·04: 실제 필터 체인에서 CSRF 토큰 발급(MEMBER_AUTH 1.1)과 상태 변경 요청 검증(COMMON 1.3)을 확인한다.
    csrf() 후처리기를 쓰지 않고, 프론트엔드처럼 발급받은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더에 그대로 넣는다.
    이메일 인증 서비스만 MockitoBean으로 바꿔 Redis·메일 없이 CSRF 통과 여부를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CsrfProtectionTests {

    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String REQUEST_CODE_PATH = "/api/v1/auth/email-verification";
    private static final String REQUEST_CODE_BODY = "{\"email\":\"user@example.com\"}";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    // 프론트엔드처럼 CSRF 토큰 쿠키를 받아 그 값을 꺼낸다
    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private static MockHttpServletRequestBuilder requestCode() {
        return post(REQUEST_CODE_PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST_CODE_BODY);
    }

    @Test
    @DisplayName("GET /api/v1/auth/csrf는 로그인 없이 204이고, JavaScript가 읽을 수 있는 XSRF-TOKEN 쿠키를 명세 속성으로 발급한다")
    void issuesReadableCsrfCookie() throws Exception {
        // when
        MockHttpServletResponse response = mockMvc.perform(get(CSRF_PATH))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().exists(XSRF_COOKIE))
                .andExpect(cookie().httpOnly(XSRF_COOKIE, false))
                .andExpect(cookie().secure(XSRF_COOKIE, true))
                .andExpect(cookie().path(XSRF_COOKIE, "/"))
                .andReturn().getResponse();

        // then: SameSite는 Servlet 쿠키 속성으로 기록돼 MockMvc의 Set-Cookie 문자열에는 보이지 않으므로 쿠키 속성으로 확인한다
        Cookie cookie = response.getCookie(XSRF_COOKIE);
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("만료·변조된 access_token 쿠키가 있어도 CSRF 토큰을 발급받을 수 있다")
    void issuesCsrfTokenWithInvalidAccessToken() throws Exception {
        // 만료된 로그인 사용자가 재발급(POST)에 쓸 토큰을 받지 못하면 갇힌다
        mockMvc.perform(get(CSRF_PATH).cookie(new Cookie("access_token", "not-a-jwt")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(XSRF_COOKIE));
    }

    @Test
    @DisplayName("발급받은 쿠키와 같은 값을 X-XSRF-TOKEN 헤더로 보낸 상태 변경 요청은 처리된다")
    void acceptsMatchingCookieAndHeader() throws Exception {
        // given
        String token = issueCsrfToken();

        // when & then: 이메일 인증 코드 요청
        mockMvc.perform(requestCode().cookie(new Cookie(XSRF_COOKIE, token)).header(XSRF_HEADER, token))
                .andExpect(status().isNoContent());
        then(emailVerificationService).should().requestCode(anyString(), anyString());

        // when & then: 회원가입은 CSRF를 통과해 Controller의 가입 컨텍스트 확인(401)까지 간다. 403이면 CSRF에서 막힌 것이다
        mockMvc.perform(post("/api/v1/auth/signup")
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Password123!\",\"nickname\":\"grab01\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SIGNUP_CONTEXT_INVALID"));
    }

    @Test
    @DisplayName("CSRF 토큰이 없거나 헤더와 쿠키가 다르면 403 ACCESS_DENIED이고 Controller에 닿지 않는다")
    void rejectsMissingOrMismatchedToken() throws Exception {
        // given
        String token = issueCsrfToken();

        // 쿠키·헤더 모두 없음
        mockMvc.perform(requestCode())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        // 쿠키만 있음
        mockMvc.perform(requestCode().cookie(new Cookie(XSRF_COOKIE, token)))
                .andExpect(status().isForbidden());
        // 헤더만 있음
        mockMvc.perform(requestCode().header(XSRF_HEADER, token))
                .andExpect(status().isForbidden());
        // 헤더와 쿠키가 다름
        mockMvc.perform(requestCode().cookie(new Cookie(XSRF_COOKIE, token)).header(XSRF_HEADER, issueCsrfToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(content().string(not(containsString(token))));

        then(emailVerificationService).should(never()).requestCode(anyString(), anyString());
    }

    @Test
    @DisplayName("로그인 사용자의 보호 경로 상태 변경 요청도 CSRF 토큰이 있어야 인가 단계로 넘어간다")
    void requiresTokenOnProtectedPath() throws Exception {
        // given
        Cookie accessToken = new Cookie("access_token",
                jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value());
        String token = issueCsrfToken();

        // when & then: 토큰 없음 → CSRF에서 403
        mockMvc.perform(put("/api/v1/drops/1/wish").cookie(accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        // 토큰 있음 → CSRF를 통과해 Controller까지 간다(DROP·회원이 없어 비즈니스 오류가 되더라도 403은 아니다)
        mockMvc.perform(put("/api/v1/drops/1/wish").cookie(accessToken, new Cookie(XSRF_COOKIE, token)).header(XSRF_HEADER, token))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("인증 쿠키 중 email_signup_token은 HttpOnly이고, XSRF-TOKEN만 JavaScript가 읽을 수 있다")
    void exposesOnlyCsrfCookieToJavaScript() throws Exception {
        // given
        given(emailVerificationService.confirmCode(anyString(), anyString()))
                .willReturn("Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo");
        String token = issueCsrfToken();

        // when & then: access_token·refresh_token 발급은 GR-34에서 같은 방식으로 확인한다
        mockMvc.perform(post(REQUEST_CODE_PATH + "/confirm")
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"code\":\"048213\"}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().httpOnly("email_signup_token", true));
        mockMvc.perform(get(CSRF_PATH))
                .andExpect(cookie().httpOnly(XSRF_COOKIE, false));
    }
}
