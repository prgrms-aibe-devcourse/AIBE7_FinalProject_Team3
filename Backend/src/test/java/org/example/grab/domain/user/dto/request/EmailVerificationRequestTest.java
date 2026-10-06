package org.example.grab.domain.user.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private List<String> validate(String email) {
        return validator.validate(new EmailVerificationRequest(email)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    @Test
    @DisplayName("이메일의 앞뒤 공백을 제거하고 소문자로 정규화한다")
    void normalizesEmail() {
        // when
        EmailVerificationRequest request = new EmailVerificationRequest("  User@Example.COM \t");

        // then
        assertThat(request.email()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("유효한 이메일은 위반이 없다")
    void acceptsValidEmail() {
        // when & then
        assertThat(validate("User@Example.com")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("이메일이 null, 빈 문자열, 공백뿐이면 필수값 위반 하나만 나온다")
    void reportsOnlyRequiredViolation(String email) {
        // when & then
        assertThat(validate(email)).containsExactly("이메일은 필수입니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"userexample.com", "us er@example.com"})
    @DisplayName("이메일 규칙을 위반하면 이메일 규칙 위반 하나만 나온다")
    void reportsOnlyEmailPolicyViolation(String email) {
        // when & then
        assertThat(validate(email)).hasSize(1).doesNotContain("이메일은 필수입니다.");
    }

    @Test
    @DisplayName("toString은 이메일을 가린다")
    void masksEmailInToString() {
        // when
        String text = new EmailVerificationRequest("user@example.com").toString();

        // then
        assertThat(text).doesNotContain("user@example.com").contains("masked");
    }
}
