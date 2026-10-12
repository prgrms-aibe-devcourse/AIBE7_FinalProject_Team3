package org.example.grab;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.user.service.EmailVerificationService;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-61 M04-02: SecurityConfig가 적용된 실제 필터 체인에서 이메일 인증 두 경로가 비로그인에 열려 있는지 확인한다.
    서비스는 MockitoBean으로 바꿔 Redis·메일 없이 인가 결과만 본다. 서비스 동작은 Controller·서비스 테스트가 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailVerificationSecurityTests {

    private static final String REQUEST_PATH = "/api/v1/auth/email-verification";
    private static final String CONFIRM_PATH = "/api/v1/auth/email-verification/confirm";
    private static final String REQUEST_BODY = "{\"email\":\"user@example.com\"}";
    private static final String CONFIRM_BODY = "{\"email\":\"user@example.com\",\"code\":\"048213\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("비로그인 POST 코드 요청은 인증 없이 Controller까지 도달해 204")
    void exposesCodeRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(post(REQUEST_PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isNoContent());
        then(emailVerificationService).should().requestCode(anyString(), anyString());
    }

    @Test
    @DisplayName("비로그인 POST 코드 확인은 인증 없이 Controller까지 도달해 204와 가입 컨텍스트 쿠키")
    void exposesCodeConfirmWithoutAuthentication() throws Exception {
        // given
        given(emailVerificationService.confirmCode(anyString(), anyString()))
                .willReturn("Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo");

        // when & then
        mockMvc.perform(post(CONFIRM_PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("email_signup_token"));
    }

    @Test
    @DisplayName("만료·변조된 access_token 쿠키가 있어도 두 경로는 토큰 검증 401 없이 처리된다")
    void ignoresInvalidAccessTokenCookie() throws Exception {
        // given
        given(emailVerificationService.confirmCode(anyString(), anyString()))
                .willReturn("Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo");
        Cookie invalid = new Cookie("access_token", "not-a-jwt");

        // when & then: 로그아웃하지 않은 채 쿠키가 만료된 사용자도 가입 절차를 진행할 수 있다
        mockMvc.perform(post(REQUEST_PATH).with(csrf()).cookie(invalid).contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(CONFIRM_PATH).with(csrf()).cookie(invalid).contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("같은 경로라도 POST가 아니면 열지 않아 비로그인은 401")
    void keepsOtherMethodsProtected() throws Exception {
        mockMvc.perform(get(REQUEST_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        then(emailVerificationService).should(never()).requestCode(anyString(), anyString());
    }

    @Test
    @DisplayName("열어 둔 경로 밖의 /api/v1/auth 경로는 비로그인 401")
    void keepsOtherAuthPathsProtected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/other").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        // 회원가입은 POST /api/v1/auth/signup만 연다(GR-30). 다른 메서드와 하위 경로는 계속 막힌다
        mockMvc.perform(get("/api/v1/auth/signup"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/auth/signup/other").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}
