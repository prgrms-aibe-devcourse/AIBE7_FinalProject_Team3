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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
    private static final String VALID_NICKNAME = "드롭헌터";

    private static final String LENGTH_REASON = "비밀번호는 8자 이상 64자 이하여야 합니다.";
    private static final String WHITESPACE_REASON = "비밀번호에 공백을 포함할 수 없습니다.";
    private static final String CHARACTER_SET_REASON = "비밀번호는 영문, 숫자, 특수문자만 사용할 수 있습니다.";
    private static final String COMPOSITION_REASON = "비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.";

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
    @DisplayName("phone 없는 유효 요청은 검증을 통과하고 공통 성공 응답으로 직렬화된다")
    // 테스트 전용 Controller의 응답이므로 DB 회원 생성 완료를 의미하지 않는다. 실제 응답 data(userId 등)는 GR-29·GR-30에서 확인한다
    void respondsWithCommonSuccessForValidRequestWithoutPhone() throws Exception {
        // when & then
        postSignup("{\"password\":\"Password1!\",\"nickname\":\"드롭헌터\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(controller.received).isEqualTo(new SignupRequest("Password1!", "드롭헌터"));
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

    // 첫 값은 JSON 문자열 안에 그대로 넣는 표현이다(탭은 JSON 이스케이프 \t로 적는다). 규칙별 경계값은 PasswordValidatorTest가 다룬다
    static Stream<Arguments> passwordPolicyViolations() {
        return Stream.of(
                Arguments.of("Pass1!a", LENGTH_REASON),
                Arguments.of("Password1!" + "a".repeat(55), LENGTH_REASON),
                Arguments.of("Pass word1!", WHITESPACE_REASON),
                Arguments.of("Pass\\tword1!", WHITESPACE_REASON),
                // 비밀번호는 trim하지 않으므로 공백만 있는 값은 필수값 위반이 아니라 비밀번호 규칙 위반이다(M00-02)
                Arguments.of("        ", WHITESPACE_REASON),
                Arguments.of("Password1!비밀", CHARACTER_SET_REASON),
                Arguments.of("Password1", COMPOSITION_REASON)
        );
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("passwordPolicyViolations")
    @DisplayName("비밀번호 정책만 위반하면 400, INVALID_PASSWORD, password 필드 오류 하나로 응답한다")
    void respondsWithInvalidPasswordForPasswordPolicyViolation(String jsonPassword, String expectedReason) throws Exception {
        // when & then
        postSignup("{\"password\":\"" + jsonPassword + "\",\"nickname\":\"" + VALID_NICKNAME + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.error.message").value("비밀번호가 규칙을 충족하지 않습니다."))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value(expectedReason));

        assertThat(controller.received).isNull();
    }
}
