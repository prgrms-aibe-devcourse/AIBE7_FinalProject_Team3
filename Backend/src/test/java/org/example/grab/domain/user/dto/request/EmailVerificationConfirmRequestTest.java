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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationConfirmRequestTest {

    private static final String VALID_EMAIL = "user@example.com";
    private static final String CODE_REQUIRED = "인증 코드는 필수입니다.";
    private static final String CODE_FORMAT = "인증 코드는 숫자 6자리여야 합니다.";

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

    private List<String> validateCode(String code) {
        return validator.validate(new EmailVerificationConfirmRequest(VALID_EMAIL, code)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    @Test
    @DisplayName("이메일은 정규화하고 인증 코드는 가공하지 않는다")
    void normalizesEmailOnly() {
        // when
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(" User@Example.COM ", " 123456 ");

        // then
        assertThat(request.email()).isEqualTo(VALID_EMAIL);
        assertThat(request.code()).isEqualTo(" 123456 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "000000", "999999"})
    @DisplayName("숫자 6자리 인증 코드는 위반이 없다")
    void acceptsSixDigitCode(String code) {
        // when & then
        assertThat(validateCode(code)).isEmpty();
    }

    @Test
    @DisplayName("인증 코드가 null이면 필수값 위반 하나만 나온다")
    void reportsOnlyRequiredViolationForNullCode() {
        // when & then
        assertThat(validateCode(null)).containsExactly(CODE_REQUIRED);
    }

    @ParameterizedTest
    // 빈 문자열은 필수값 위반과 형식 위반이 겹치지 않도록 형식 위반 하나로 보고한다
    @ValueSource(strings = {"", "12345", "1234567", "12345a", " 123456", "123 456", "１２３４５６"})
    @DisplayName("숫자 6자리가 아니면 형식 위반 하나만 나온다")
    void reportsOnlyFormatViolation(String code) {
        // when & then
        assertThat(validateCode(code)).containsExactly(CODE_FORMAT);
    }

    @Test
    @DisplayName("위반 문구와 toString에 인증 코드와 이메일이 들어가지 않는다")
    void excludesCodeAndEmail() {
        // given
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(VALID_EMAIL, "12345a");

        // when
        List<String> messages = validator.validate(request).stream().map(ConstraintViolation::getMessage).toList();

        // then
        assertThat(messages).noneMatch(message -> message.contains("12345a"));
        assertThat(request.toString()).doesNotContain("12345a", VALID_EMAIL).contains("masked");
    }
}
