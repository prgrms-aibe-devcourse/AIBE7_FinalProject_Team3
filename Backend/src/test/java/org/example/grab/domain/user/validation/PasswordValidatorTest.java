package org.example.grab.domain.user.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotEmpty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.grab.domain.user.validation.PasswordValidator.CHARACTER_SET_MESSAGE;
import static org.example.grab.domain.user.validation.PasswordValidator.COMPOSITION_MESSAGE;
import static org.example.grab.domain.user.validation.PasswordValidator.LENGTH_MESSAGE;
import static org.example.grab.domain.user.validation.PasswordValidator.WHITESPACE_MESSAGE;

class PasswordValidatorTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    // 애노테이션과 Validator 연결까지 확인하기 위해 isValid를 직접 부르지 않고 제약이 붙은 대상을 검증한다
    private record PasswordHolder(@ValidPassword String password) {
    }

    // 회원가입 DTO처럼 필수값 제약과 함께 붙였을 때 두 제약이 같은 값에 동시에 위반을 보고하지 않는지 확인한다
    private record RequiredPasswordHolder(@NotEmpty @ValidPassword String password) {
    }

    // 테스트 시작 전 한번만 실행
    // 테스트 과정에서 필요한 검증 엔진을 만드는 과정
    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    // 테스트 종료 시 생성되었던 validatorFactory 종료
    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private List<String> validate(String password) {
        return validator.validate(new PasswordHolder(password)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    // 기본 @NotEmpty 문구는 로케일에 따라 달라지므로 문구 대신 위반한 제약 타입으로 비교한다
    private List<Class<? extends Annotation>> validateRequired(String password) {
        return validator.validate(new RequiredPasswordHolder(password)).stream()
                .<Class<? extends Annotation>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .toList();
    }

    // 길이 외 조건(영문·숫자·특수문자 포함, 공백 없음)을 충족하면서 길이만 원하는 값으로 맞춘다
    private static String passwordOfLength(int length) {
        return "Aa1!" + "a".repeat(length - 4);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null과 빈 문자열은 필수값 제약에 맡기고 위반을 보고하지 않는다")
    void acceptsNullAndEmpty(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 64})
    @DisplayName("8자와 64자 비밀번호는 통과한다")
    void acceptsBoundaryLength(int length) {
        // when
        List<String> messages = validate(passwordOfLength(length));

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 65})
    @DisplayName("7자와 65자 비밀번호는 길이 사유로 거부한다")
    void rejectsOutOfRangeLength(int length) {
        // when
        List<String> messages = validate(passwordOfLength(length));

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678!", "Password!", "Password1", "password", "12345678", "!!!!!!!!"})
    @DisplayName("영문·숫자·특수문자 중 하나라도 없으면 문자 조합 사유로 거부한다")
    // 앞의 세 값은 한 종류만, 뒤의 세 값은 두 종류가 빠진 경우다
    void rejectsMissingCharacterType(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).containsExactly(COMPOSITION_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password1!", "PASSWORD1!"})
    @DisplayName("영문은 소문자나 대문자 한 종류만 있어도 통과한다")
    void acceptsSingleLetterCase(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
            ":", ";", "<", "=", ">", "?", "@",
            "[", "\\", "]", "^", "_", "`",
            "{", "|", "}", "~"
    })
    @DisplayName("ASCII 특수문자 32개는 각각 특수문자로 인정된다")
    void acceptsEachAsciiSpecialCharacter(String special) {
        // when
        List<String> messages = validate("Password1" + special);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " Password1!",
            "Password1! ",
            "Pass word1!",
            "Pass\tword1!",
            "Pass\nword1!",
            "Pass\rword1!",
            "Pass\u3000word1!",
            "        "
    })
    @DisplayName("스페이스·탭·개행·전각 스페이스가 있으면 공백 사유로 거부한다")
    // 공백 문자는 모두 0x21~0x7E 밖이라 허용 문자 조건도 함께 위반하므로, 공백 조건만 위반하는 입력은 만들 수 없다
    // 그래서 공백 외 조건(길이·문자 조합)은 충족하는 값으로 공백 단계가 허용 문자 단계보다 먼저 걸리는지 확인한다
    // 공백만 8자인 값도 필수값이 아닌 공백 사유로 거부되는지 함께 확인한다
    void rejectsWhitespace(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).containsExactly(WHITESPACE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Password1!한",
            "Password1!\uD83D\uDE00",
            "Password1!\uFF11",
            "\uFF30assword1!",
            "Pass\u00A0word1!"
    })
    @DisplayName("한글·이모지·전각 문자·NBSP가 섞이면 허용 문자 사유로 거부한다")
    // 전각 숫자(U+FF11)·전각 영문(U+FF30)은 Character.isDigit·isLetter로는 참이 되므로 ASCII 범위로 거르는지 확인한다
    // NBSP(U+00A0)는 isWhitespace가 거짓이라 공백이 아닌 허용 문자 사유가 된다
    void rejectsDisallowedCharacters(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).containsExactly(CHARACTER_SET_MESSAGE);
    }

    @Test
    @DisplayName("허용 문자 외 문자도 char 수로 길이를 센다")
    // 이모지는 char 2개(서로게이트 쌍)이므로 6자 + 이모지는 8자로 길이를 통과하고 허용 문자 사유가 된다
    void countsLengthByCharIncludingDisallowedCharacters() {
        // when
        List<String> messages = validate("Pass1!\uD83D\uDE00");

        // then
        assertThat(messages).containsExactly(CHARACTER_SET_MESSAGE);
    }

    @Test
    @DisplayName("네 조건을 모두 위반하면 길이 사유 하나만 보고한다")
    // 3자, 중간 공백, 한글, 영문·숫자·특수문자 없음
    void reportsLengthWhenAllConditionsFail() {
        // when
        List<String> messages = validate("한 글");

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("공백·허용 문자·문자 조합을 함께 위반하면 공백 사유 하나만 보고한다")
    void reportsWhitespaceWhenRemainingConditionsFail() {
        // when
        List<String> messages = validate("비밀 번호 입니다");

        // then
        assertThat(messages).containsExactly(WHITESPACE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pa 1!", "   "})
    @DisplayName("길이와 공백을 함께 위반하면 길이 사유 하나만 보고한다")
    void reportsLengthBeforeWhitespace(String password) {
        // when
        List<String> messages = validate(password);

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("길이와 허용 문자를 함께 위반하면 길이 사유 하나만 보고한다")
    void reportsLengthBeforeCharacterSet() {
        // when
        List<String> messages = validate("비밀번호");

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("길이와 문자 조합을 함께 위반하면 길이 사유 하나만 보고한다")
    void reportsLengthBeforeComposition() {
        // when
        List<String> messages = validate("abc");

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("공백과 허용 문자를 함께 위반하면 공백 사유 하나만 보고한다")
    void reportsWhitespaceBeforeCharacterSet() {
        // when
        List<String> messages = validate("비밀 번호1234!");

        // then
        assertThat(messages).containsExactly(WHITESPACE_MESSAGE);
    }

    @Test
    @DisplayName("공백과 문자 조합을 함께 위반하면 공백 사유 하나만 보고한다")
    void reportsWhitespaceBeforeComposition() {
        // when
        List<String> messages = validate("pass word");

        // then
        assertThat(messages).containsExactly(WHITESPACE_MESSAGE);
    }

    @Test
    @DisplayName("허용 문자와 문자 조합을 함께 위반하면 허용 문자 사유 하나만 보고한다")
    void reportsCharacterSetBeforeComposition() {
        // when
        List<String> messages = validate("비밀번호1234");

        // then
        assertThat(messages).containsExactly(CHARACTER_SET_MESSAGE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("필수값 제약과 함께 쓰면 null과 빈 문자열은 필수값 위반 하나만 나온다")
    void reportsOnlyRequiredViolationForNullAndEmpty(String password) {
        // when
        List<Class<? extends Annotation>> violatedConstraints = validateRequired(password);

        // then
        assertThat(violatedConstraints).containsExactly(NotEmpty.class);
    }

    @Test
    @DisplayName("필수값 제약과 함께 쓰면 공백만 있는 값은 필수값이 아닌 비밀번호 규칙 위반 하나만 나온다")
    // 비밀번호는 trim하지 않으므로 공백만 있는 값은 INVALID_PASSWORD 대상이다(MEMBER_AUTH.md 1.2)
    void reportsOnlyPasswordViolationForBlank() {
        // when
        List<Class<? extends Annotation>> violatedConstraints = validateRequired("   ");

        // then
        assertThat(violatedConstraints).containsExactly(ValidPassword.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password1!", " Password1! ", "Pass\u00A0word1!"})
    @DisplayName("검증을 통과하든 실패하든 비밀번호 값은 바뀌지 않는다")
    // 검증 단계에서 trim·치환·절단한 값을 다시 넣지 않는지 확인한다
    void keepsOriginalPassword(String password) {
        // given
        PasswordHolder holder = new PasswordHolder(password);

        // when
        validator.validate(holder);

        // then
        assertThat(holder.password()).isSameAs(password);
    }
}
