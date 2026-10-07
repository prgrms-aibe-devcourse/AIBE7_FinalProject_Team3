package org.example.grab.domain.user.validation;

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
import static org.example.grab.domain.user.validation.EmailValidator.FORMAT_MESSAGE;
import static org.example.grab.domain.user.validation.EmailValidator.LENGTH_MESSAGE;
import static org.example.grab.domain.user.validation.EmailValidator.WHITESPACE_MESSAGE;

class EmailValidatorTest {

    private static final String DOMAIN = "@example.com";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    // 애노테이션과 Validator 연결까지 확인하기 위해 isValid를 직접 부르지 않고 제약이 붙은 대상을 검증한다
    private record EmailHolder(@ValidEmail String email) {
    }

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
        return validator.validate(new EmailHolder(email)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    // 로컬 부분 길이를 조절해 전체 길이가 length인 주소를 만든다
    private static String emailOfLength(int length) {
        return "a".repeat(length - DOMAIN.length()) + DOMAIN;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null과 빈 문자열은 필수값 제약에 맡기고 위반을 보고하지 않는다")
    void acceptsNullAndEmpty(String email) {
        // when
        List<String> messages = validate(email);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.com", "first.last+tag@sub.example.co.kr", "user_1@example.io", "a@b.c"})
    @DisplayName("로컬 부분과 점으로 구분한 도메인을 가진 주소는 통과한다")
    void acceptsValidEmail(String email) {
        // when
        List<String> messages = validate(email);

        // then
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("254자 주소는 통과한다")
    void acceptsMaxLength() {
        // when
        List<String> messages = validate(emailOfLength(254));

        // then
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("254자를 넘으면 길이 사유 하나만 보고한다")
    void rejectsTooLong() {
        // when
        List<String> messages = validate(emailOfLength(255));

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("길이와 형식을 함께 위반하면 길이 사유만 보고한다")
    // 필수값 → 길이 → 형식 우선순위(MEMBER_AUTH.md 1.2)에서 길이가 형식보다 먼저인지 확인하는 테스트
    void reportsLengthBeforeFormat() {
        // given
        String tooLongWithSpace = "a b" + emailOfLength(253);

        // when
        List<String> messages = validate(tooLongWithSpace);

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"us er@example.com", "user@exa mple.com", "user\t@example.com", "user@example.com　x"})
    @DisplayName("중간 공백이 있으면 공백 사유 하나만 보고한다")
    void rejectsWhitespace(String email) {
        // when
        List<String> messages = validate(email);

        // then
        assertThat(messages).containsExactly(WHITESPACE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "userexample.com", "user@", "@example.com", "user@example", "user@@example.com", "a@b@example.com",
            "user@.com", "user@example.", "user@example..com"
    })
    @DisplayName("@·도메인 구조가 맞지 않으면 형식 사유 하나만 보고한다")
    void rejectsInvalidFormat(String email) {
        // when
        List<String> messages = validate(email);

        // then
        assertThat(messages).containsExactly(FORMAT_MESSAGE);
    }
}
