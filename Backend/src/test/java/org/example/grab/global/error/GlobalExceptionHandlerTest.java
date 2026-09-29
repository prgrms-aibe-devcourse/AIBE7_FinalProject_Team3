package org.example.grab.global.error;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    // global 테스트가 특정 도메인을 참조하지 않도록 테스트 전용 제약과 오류 코드를 쓴다
    private static ValidationErrorCodeResolver resolver() {
        ConstraintErrorCodeMapping mapping = () -> Map.of(AlwaysInvalid.class, TestErrorCode.TEST_INVALID);
        return new ValidationErrorCodeResolver(List.of(mapping));
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(resolver()))
                .build();
    }

    @Test
    @DisplayName("BusinessException은 ErrorCode의 상태·코드·메시지로 응답한다")
    void handlesBusinessException() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.error.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("요청 값 검증 실패는 VALIDATION_FAILED와 필드 오류 목록으로 응답한다")
    void handlesValidationFailure() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").isNotEmpty());
    }

    @Test
    @DisplayName("매핑된 제약만 위반하면 매핑된 오류 코드와 메시지로 응답한다")
    void respondsWithMappedErrorCode() throws Exception {
        mockMvc.perform(post("/test/mapped-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first\":\"a\",\"second\":\"b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TEST_INVALID"))
                .andExpect(jsonPath("$.error.message").value("테스트 제약 위반입니다."))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(2));
    }

    @Test
    @DisplayName("필드 오류 코드가 섞이면 VALIDATION_FAILED로 응답하고 필드 오류를 선언 순서로 담는다")
    void respondsWithValidationFailedForMixedCodes() throws Exception {
        mockMvc.perform(post("/test/mixed-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first\":\"a\",\"second\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("요청 값 검증에 실패했습니다."))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("first"))
                .andExpect(jsonPath("$.error.fieldErrors[1].field").value("second"));
    }

    @Test
    @DisplayName("요청 파라미터 타입 오류는 400 INVALID_REQUEST로 응답한다")
    void handlesParameterTypeMismatch() throws Exception {
        mockMvc.perform(get("/test/type").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @RestController
    static class TestController {

        @PostMapping("/test/business")
        void business() {
            throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        }

        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody TestRequest request) {
        }

        @PostMapping("/test/mapped-validation")
        void mappedValidation(@Valid @RequestBody MappedRequest request) {
        }

        @PostMapping("/test/mixed-validation")
        void mixedValidation(@Valid @RequestBody MixedRequest request) {
        }

        @GetMapping("/test/type")
        void type(@RequestParam int page) {
        }
    }

    record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    record MappedRequest(@AlwaysInvalid String first, @AlwaysInvalid String second) {
    }

    record MixedRequest(@AlwaysInvalid String first, @NotBlank String second) {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidValidator.class)
    @interface AlwaysInvalid {
        String message() default "항상 거부합니다.";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysInvalidValidator implements ConstraintValidator<AlwaysInvalid, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    private enum TestErrorCode implements ErrorCode {
        TEST_INVALID;

        @Override
        public HttpStatus getStatus() {
            return HttpStatus.BAD_REQUEST;
        }

        @Override
        public String getCode() {
            return name();
        }

        @Override
        public String getMessage() {
            return "테스트 제약 위반입니다.";
        }
    }
}
