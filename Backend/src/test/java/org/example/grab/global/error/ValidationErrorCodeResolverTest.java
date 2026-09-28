package org.example.grab.global.error;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationErrorCodeResolverTest {

    private static ValidatorFactory validatorFactory;
    private static SpringValidatorAdapter springValidator;

    // global 테스트가 특정 도메인을 참조하지 않도록 테스트 전용 제약과 오류 코드를 쓴다
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
        TEST_INVALID, OTHER_INVALID;

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
            return name();
        }
    }

    private record MappedRequest(@AlwaysInvalid String value) {
    }

    private record StandardRequest(@NotBlank String value) {
    }

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        // 컨트롤러의 @Valid와 같이 ConstraintViolation을 원본으로 품은 FieldError를 만들기 위해 Spring 어댑터로 검증한다
        springValidator = new SpringValidatorAdapter(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static FieldError validateSingleField(Object target) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(target, "request");
        springValidator.validate(target, result);
        assertThat(result.getFieldErrors()).hasSize(1);
        return result.getFieldErrors().get(0);
    }

    private static ConstraintErrorCodeMapping mapping(ErrorCode errorCode) {
        return () -> Map.of(AlwaysInvalid.class, errorCode);
    }

    @Test
    @DisplayName("등록된 제약의 위반은 매핑된 오류 코드로 바꾼다")
    void resolvesMappedConstraint() {
        // given
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(mapping(TestErrorCode.TEST_INVALID)));

        // when
        ErrorCode errorCode = resolver.resolve(validateSingleField(new MappedRequest("값")));

        // then
        assertThat(errorCode).isEqualTo(TestErrorCode.TEST_INVALID);
    }

    @Test
    @DisplayName("등록되지 않은 제약의 위반은 VALIDATION_FAILED로 둔다")
    // 매핑을 등록하지 않은 다른 DTO의 표준 제약이 도메인 오류 코드로 바뀌지 않는지 확인하는 테스트(GR-28 M06-04)
    void resolvesUnmappedConstraintToValidationFailed() {
        // given
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(mapping(TestErrorCode.TEST_INVALID)));

        // when
        ErrorCode errorCode = resolver.resolve(validateSingleField(new StandardRequest("")));

        // then
        assertThat(errorCode).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Bean Validation 위반이 아닌 필드 오류는 VALIDATION_FAILED로 둔다")
    // 타입 변환 실패 등은 ConstraintViolation이 없으므로 예외 없이 기본 코드가 나오는지 확인하는 테스트
    void resolvesFieldErrorWithoutConstraintViolation() {
        // given
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(mapping(TestErrorCode.TEST_INVALID)));
        FieldError typeMismatch = new FieldError("request", "value", "형식이 올바르지 않습니다.");

        // when
        ErrorCode errorCode = resolver.resolve(typeMismatch);

        // then
        assertThat(errorCode).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("매핑이 하나도 없어도 모든 위반을 VALIDATION_FAILED로 둔다")
    void resolvesWithoutMappings() {
        // given
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of());

        // when
        ErrorCode errorCode = resolver.resolve(validateSingleField(new MappedRequest("값")));

        // then
        assertThat(errorCode).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("같은 제약에 서로 다른 오류 코드가 등록되면 생성 시점에 실패한다")
    // 등록 순서에 따라 응답 코드가 달라지는 상황을 기동 단계에서 막는지 확인하는 테스트
    void rejectsConflictingMappings() {
        // given
        List<ConstraintErrorCodeMapping> mappings = List.of(
                mapping(TestErrorCode.TEST_INVALID),
                mapping(TestErrorCode.OTHER_INVALID)
        );

        // when & then
        assertThatThrownBy(() -> new ValidationErrorCodeResolver(mappings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_INVALID")
                .hasMessageContaining("OTHER_INVALID");
    }
}
