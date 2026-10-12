package org.example.grab.domain.auth.dto.request;

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

// GR-34 M02-01: 로그인 요청은 이메일 정규화와 필수값만 검사한다(M00-04)
class LoginRequestTest {

    private static final String EMAIL_REQUIRED = "이메일은 필수입니다.";
    private static final String PASSWORD_REQUIRED = "비밀번호는 필수입니다.";

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

    private List<String> validate(LoginRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    @Test
    @DisplayName("이메일은 앞뒤 공백 제거·소문자로 정규화하고 비밀번호는 가공하지 않는다")
    void normalizesEmailOnly() {
        // when
        LoginRequest request = new LoginRequest(" User@Example.COM ", " Password123! ");

        // then
        assertThat(request.email()).isEqualTo("user@example.com");
        assertThat(request.password()).isEqualTo(" Password123! ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "　"})
    @DisplayName("이메일이 없거나 공백뿐이면 필수값 위반이다")
    void rejectsMissingEmail(String email) {
        assertThat(validate(new LoginRequest(email, "Password123!"))).containsExactly(EMAIL_REQUIRED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("비밀번호가 없거나 빈 문자열이면 필수값 위반이다")
    void rejectsMissingPassword(String password) {
        assertThat(validate(new LoginRequest("user@example.com", password))).containsExactly(PASSWORD_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "a b@example.com"})
    @DisplayName("이메일 형식은 검사하지 않는다(형식 오류는 일치하는 회원이 없어 INVALID_CREDENTIALS가 된다)")
    void doesNotValidateEmailFormat(String email) {
        assertThat(validate(new LoginRequest(email, "Password123!"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "   ", "nodigits!!!"})
    @DisplayName("비밀번호 규칙과 공백뿐인 값은 검사하지 않는다(가입 규칙이 바뀌어도 기존 회원이 로그인할 수 있다)")
    void doesNotValidatePasswordRules(String password) {
        assertThat(validate(new LoginRequest("user@example.com", password))).isEmpty();
    }

    @Test
    @DisplayName("toString은 이메일과 비밀번호를 가린다")
    void masksCredentialsInToString() {
        // when
        String text = new LoginRequest("user@example.com", "Password123!").toString();

        // then
        assertThat(text).doesNotContain("user@example.com").doesNotContain("Password123!");
    }
}
