package org.example.grab.domain.user.dto.request;

import jakarta.validation.Valid;
import org.example.grab.domain.user.error.UserConstraintErrorCodeMapping;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    이메일 인증 요청의 JSON 바인딩 → 정규화 → 검증 → 오류 변환 경로를 MockMvc로 확인한다(GR-61 M01-03).
    실제 Controller는 M04에서 만들므로 받은 요청만 기록하는 테스트 전용 Controller를 쓴다.
    필드별 규칙의 경계값은 EmailValidatorTest·각 DTO 테스트가 다루고, 여기서는 응답 오류 코드와 우선순위를 확인한다.
 */
class EmailVerificationRequestWebValidationTest {

    private static final String REQUEST_PATH = "/test/email-verification";
    private static final String CONFIRM_PATH = "/test/email-verification/confirm";

    private final TestController controller = new TestController();
    private MockMvc mockMvc;

    @RestController
    static class TestController {

        private Object received;

        @PostMapping(REQUEST_PATH)
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void request(@Valid @RequestBody EmailVerificationRequest request) {
            this.received = request;
        }

        @PostMapping(CONFIRM_PATH)
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void confirm(@Valid @RequestBody EmailVerificationConfirmRequest request) {
            this.received = request;
        }
    }

    @BeforeEach
    void setUp() {
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(new UserConstraintErrorCodeMapping()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .build();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("코드 요청 본문은 정규화된 이메일로 바인딩되어 Controller에 도달한다")
    void bindsNormalizedEmail() throws Exception {
        // when
        postJson(REQUEST_PATH, "{\"email\":\"  User@Example.COM \"}").andExpect(status().isNoContent());

        // then
        assertThat(controller.received).isEqualTo(new EmailVerificationRequest("user@example.com"));
    }

    @Test
    @DisplayName("이메일이 없으면 VALIDATION_FAILED, 규칙 위반이면 INVALID_EMAIL로 응답한다")
    void resolvesEmailErrorCodes() throws Exception {
        // when & then
        postJson(REQUEST_PATH, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value("이메일은 필수입니다."));
        postJson(REQUEST_PATH, "{\"email\":\"userexample.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value("올바른 이메일 형식이 아닙니다."));
        postJson(REQUEST_PATH, "{\"email\":\"" + "a".repeat(243) + "@example.com\"}")
                .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value("이메일은 254자 이하여야 합니다."));

        assertThat(controller.received).isNull();
    }

    @Test
    @DisplayName("인증 코드 형식 위반은 VALIDATION_FAILED이고 응답에 입력한 코드가 들어가지 않는다")
    void resolvesCodeFormatToValidationFailed() throws Exception {
        // when & then
        postJson(CONFIRM_PATH, "{\"email\":\"user@example.com\",\"code\":\"98765x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("code"))
                .andExpect(content().string(not(containsString("98765x"))));

        assertThat(controller.received).isNull();
    }

    @Test
    @DisplayName("이메일과 인증 코드가 함께 틀리면 VALIDATION_FAILED이고 fieldErrors는 email → code 순서다")
    // 코드가 섞이면 VALIDATION_FAILED, 필드 순서는 DTO 선언 순서다(MEMBER_AUTH.md 1.2.3의 다중 위반 규칙과 같은 방식)
    void resolvesMultipleViolations() throws Exception {
        // when & then
        postJson(CONFIRM_PATH, "{\"email\":\"userexample.com\",\"code\":\"1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("email"))
                .andExpect(jsonPath("$.error.fieldErrors[1].field").value("code"));
    }

    @Test
    @DisplayName("인증 코드가 맞는 형식이면 이메일 위반만 INVALID_EMAIL로 응답한다")
    void resolvesEmailErrorOnConfirm() throws Exception {
        // when & then
        postJson(CONFIRM_PATH, "{\"email\":\"userexample.com\",\"code\":\"123456\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL"));
    }
}
