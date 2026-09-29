package org.example.grab.global.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueMaskingValidatorTest {

    private static final String SECRET = "비공개원문값";

    private static SensitiveValueMaskingValidator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = new SensitiveValueMaskingValidator();
        validator.afterPropertiesSet();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    private record FlatRequest(
            @SensitiveValue @Size(min = 100) String secret,
            @Size(min = 100) String plain
    ) {
    }

    private record Inner(@SensitiveValue @Size(min = 100) String secret) {
    }

    private record NestedRequest(@Valid Inner inner) {
    }

    private record ContainerRequest(@SensitiveValue List<@Size(min = 100) String> secrets) {
    }

    private static BindingResult validate(Object target) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(target, "request");
        validator.validate(target, result);
        return result;
    }

    @Test
    @DisplayName("@SensitiveValue 필드의 거부된 값은 가리고, 표시하지 않은 필드는 원래 값을 둔다")
    void masksOnlySensitiveField() {
        // when
        BindingResult result = validate(new FlatRequest(SECRET, SECRET));

        // then
        assertThat(result.getFieldError("secret").getRejectedValue()).isEqualTo(SensitiveValueMaskingValidator.MASKED_VALUE);
        assertThat(result.getFieldError("plain").getRejectedValue()).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("가린 필드 오류는 문자열 표현에도 원문이 없고, 원본 위반 정보는 유지한다")
    // 로그에 남는 예외 메시지는 FieldError.toString()으로 만들어지고, 오류 코드 선택은 원본 위반의 제약 타입을 쓴다
    void keepsViolationWhileHidingValueFromToString() {
        // when
        FieldError fieldError = validate(new FlatRequest(SECRET, "충분히긴값".repeat(20))).getFieldError("secret");

        // then
        assertThat(fieldError.toString()).doesNotContain(SECRET);
        assertThat(fieldError.getDefaultMessage()).doesNotContain(SECRET);
        assertThat(fieldError.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAnnotation())
                .isInstanceOf(Size.class);
    }

    @Test
    @DisplayName("중첩 객체의 @SensitiveValue 필드도 가린다")
    void masksNestedSensitiveField() {
        // when
        BindingResult result = validate(new NestedRequest(new Inner(SECRET)));

        // then
        assertThat(result.getFieldError("inner.secret").getRejectedValue()).isEqualTo(SensitiveValueMaskingValidator.MASKED_VALUE);
    }

    @Test
    @DisplayName("@SensitiveValue 컬렉션의 요소 제약 위반도 가린다")
    void masksContainerElementOfSensitiveField() {
        // when
        BindingResult result = validate(new ContainerRequest(List.of(SECRET)));

        // then
        assertThat(result.getFieldErrors()).hasSize(1);
        assertThat(result.getFieldErrors().get(0).getRejectedValue()).isEqualTo(SensitiveValueMaskingValidator.MASKED_VALUE);
        assertThat(result.getFieldErrors().get(0).toString()).doesNotContain(SECRET);
    }
}
