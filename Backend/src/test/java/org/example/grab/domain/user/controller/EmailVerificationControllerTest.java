package org.example.grab.domain.user.controller;

import org.example.grab.domain.user.error.UserConstraintErrorCodeMapping;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.service.EmailVerificationService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-61 M04-01: 이메일 인증 Controller의 상태 코드·오류 코드·쿠키. 서비스는 mock이다.
    보안 필터(비인증 허용)는 M04-02에서 실제 필터 체인으로 확인한다.
 */
class EmailVerificationControllerTest {

    private static final String REQUEST_PATH = "/api/v1/auth/email-verification";
    private static final String CONFIRM_PATH = "/api/v1/auth/email-verification/confirm";
    private static final String EMAIL = "user@example.com";
    private static final String CODE = "048213";
    private static final String SIGNUP_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";

    private EmailVerificationService emailVerificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        emailVerificationService = mock(EmailVerificationService.class);
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(new UserConstraintErrorCodeMapping()));
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailVerificationController(emailVerificationService))
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .build();
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String confirmBody(String code) {
        return "{\"email\":\"" + EMAIL + "\",\"code\":\"" + code + "\"}";
    }

    @Test
    @DisplayName("코드 요청은 정규화한 이메일과 접속 IP로 서비스를 호출하고 본문 없이 204를 반환한다")
    void requestsCode() throws Exception {
        // when & then
        mockMvc.perform(postJson(REQUEST_PATH, "{\"email\":\"  User@Example.COM \"}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        }))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        then(emailVerificationService).should().requestCode(EMAIL, "203.0.113.7");
    }

    @Test
    @DisplayName("코드 요청의 이메일 형식 오류는 400 INVALID_EMAIL이고 서비스를 호출하지 않는다")
    void rejectsInvalidEmailOnRequest() throws Exception {
        // when & then
        mockMvc.perform(postJson(REQUEST_PATH, "{\"email\":\"user@localhost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL"));
        then(emailVerificationService).should(never()).requestCode(anyString(), anyString());
    }

    @Test
    @DisplayName("발송 제한에 걸리면 429 EMAIL_VERIFICATION_RESEND_TOO_SOON이다")
    void returns429WhenResendTooSoon() throws Exception {
        // given
        willThrow(new BusinessException(UserErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON))
                .given(emailVerificationService).requestCode(anyString(), anyString());

        // when & then
        mockMvc.perform(postJson(REQUEST_PATH, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_RESEND_TOO_SOON"));
    }

    @Test
    @DisplayName("코드 확인에 성공하면 본문 없이 204와 명세 속성의 가입 컨텍스트 쿠키를 반환한다")
    void issuesSignupCookieOnConfirm() throws Exception {
        // given
        given(emailVerificationService.confirmCode(EMAIL, CODE)).willReturn(SIGNUP_TOKEN);

        // when & then: HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/signup; Max-Age=900
        mockMvc.perform(postJson(CONFIRM_PATH, confirmBody(CODE)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().value("email_signup_token", SIGNUP_TOKEN))
                .andExpect(cookie().httpOnly("email_signup_token", true))
                .andExpect(cookie().secure("email_signup_token", true))
                .andExpect(cookie().sameSite("email_signup_token", "Lax"))
                .andExpect(cookie().path("email_signup_token", "/api/v1/auth/signup"))
                .andExpect(cookie().maxAge("email_signup_token", 900));
    }

    @Test
    @DisplayName("코드 형식 오류는 400 VALIDATION_FAILED이고 서비스를 호출하지 않아 시도 횟수에 들어가지 않는다")
    void rejectsInvalidCodeWithoutCallingService() throws Exception {
        // when & then
        mockMvc.perform(postJson(CONFIRM_PATH, confirmBody("12345a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        then(emailVerificationService).should(never()).confirmCode(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = UserErrorCode.class, names = {
            "EMAIL_VERIFICATION_CODE_MISMATCH",
            "EMAIL_VERIFICATION_CODE_EXPIRED",
            "EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED",
            "DUPLICATE_EMAIL"
    })
    @DisplayName("코드 확인이 실패하면 오류 코드의 상태로 응답하고 쿠키를 설정하지 않으며, 응답에 코드가 없다")
    void returnsErrorWithoutCookieOnConfirmFailure(UserErrorCode errorCode) throws Exception {
        // given
        given(emailVerificationService.confirmCode(EMAIL, CODE)).willThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(postJson(CONFIRM_PATH, confirmBody(CODE)))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.error.code").value(errorCode.getCode()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(not(containsString(CODE))))
                .andExpect(content().string(not(containsString(EMAIL))));
    }
}
