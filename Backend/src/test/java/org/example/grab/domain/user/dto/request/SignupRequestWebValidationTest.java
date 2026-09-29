package org.example.grab.domain.user.dto.request;

import jakarta.validation.Valid;
import org.example.grab.domain.user.error.UserConstraintErrorCodeMapping;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.validation.SensitiveValueMaskingValidator;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    회원가입 요청의 JSON 바인딩 → 정규화 → 검증 → 오류 변환 경로를 MockMvc로 확인한다(GR-28 M08).
    실제 회원가입 Controller·서비스는 GR-29·GR-30에서 만들므로, 받은 요청만 기록하는 테스트 전용 Controller를 쓴다.
    검증기·오류 코드 매핑·전역 예외 처리기는 운영과 같은 구성을 연결한다.
 */
class SignupRequestWebValidationTest {

    private static final String SIGNUP_PATH = "/test/signup";

    private final TestSignupController controller = new TestSignupController();
    private SensitiveValueMaskingValidator validator;
    private MockMvc mockMvc;

    @RestController
    static class TestSignupController {

        private SignupRequest received;

        @PostMapping(SIGNUP_PATH)
        @ResponseStatus(HttpStatus.CREATED)
        ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
            this.received = request;
            return ApiResponse.success(null);
        }
    }

    @BeforeEach
    void setUp() {
        // 운영의 ValidationConfig와 같은 검증기를 쓴다. standalone MockMvc는 스프링 컨텍스트의 검증기를 쓰지 않으므로 직접 넘긴다
        validator = new SensitiveValueMaskingValidator();
        validator.afterPropertiesSet();
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(new UserConstraintErrorCodeMapping()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    private ResultActions postSignup(String body) throws Exception {
        return mockMvc.perform(post(SIGNUP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("유효한 요청은 실제 SignupRequest로 바인딩·정규화되어 Controller에 도달한다")
    void bindsValidRequestToSignupRequest() throws Exception {
        // when
        postSignup("{\"password\":\"Password1!\",\"nickname\":\"  드롭헌터  \"}")
                .andExpect(status().isCreated());

        // then
        assertThat(controller.received).isNotNull();
        assertThat(controller.received.password()).isEqualTo("Password1!");
        assertThat(controller.received.nickname()).isEqualTo("드롭헌터");
    }

    @Test
    @DisplayName("검증에 실패한 요청은 Controller에 도달하지 않고 전역 예외 처리기가 회원가입 오류 코드로 응답한다")
    // user 도메인 매핑이 연결되지 않으면 INVALID_PASSWORD 대신 VALIDATION_FAILED가 나온다
    void handlesInvalidRequestWithGlobalExceptionHandler() throws Exception {
        // when
        postSignup("{\"password\":\"short\",\"nickname\":\"드롭헌터\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));

        // then
        assertThat(controller.received).isNull();
    }
}
