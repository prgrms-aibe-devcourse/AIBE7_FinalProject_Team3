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
import static org.example.grab.domain.user.validation.NicknameValidator.CHARACTER_SET_MESSAGE;
import static org.example.grab.domain.user.validation.NicknameValidator.LENGTH_MESSAGE;

class NicknameValidatorTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    // 애노테이션과 Validator 연결까지 확인하기 위해 isValid를 직접 부르지 않고 제약이 붙은 대상을 검증한다
    private record NicknameHolder(@ValidNickname String nickname) {
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

    private List<String> validate(String nickname) {
        return validator.validate(new NicknameHolder(nickname)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null과 빈 문자열은 필수값 제약에 맡기고 위반을 보고하지 않는다")
    void acceptsNullAndEmpty(String nickname) {
        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"드롭", "가나다라마바사아자차", "Grab_123", "ab", "abcdefghij", "__", "12", "드롭Hunter_1"})
    @DisplayName("2자~10자의 한글·영문·숫자·밑줄 조합은 통과한다")
    void acceptsValidNickname(String nickname) {
        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"드", "a", "가나다라마바사아자차카", "abcdefghijk"})
    @DisplayName("1자와 11자 닉네임은 길이 사유로 거부한다")
    void rejectsOutOfRangeLength(String nickname) {
        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "드롭 헌터",
            "드롭\t헌터",
            "드롭\u3000헌터",
            "drop-hunt",
            "드롭!",
            "드롭\uD83D\uDE00",
            "ㄱㄴ",
            "드롭ㅏ",
            "\uFF27rab",
            "드롭\uFF11",
            "\u00A0드롭",
            "ドロップ"
    })
    @DisplayName("공백·특수문자·이모지·한글 자모·전각 문자·다른 언어 문자가 섞이면 허용 문자 사유로 거부한다")
    // 자모(ㄱ, ㅏ)·전각 영문(U+FF27)·전각 숫자(U+FF11)·가타카나는 Character.isLetter·isDigit으로는 참이 되므로 범위로 거르는지 확인한다
    // NBSP(U+00A0)는 strip()으로 제거되지 않고 남으므로 허용 문자 사유가 된다
    void rejectsDisallowedCharacters(String nickname) {
        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(messages).containsExactly(CHARACTER_SET_MESSAGE);
    }

    @Test
    @DisplayName("길이는 코드 포인트 기준으로 센다")
    // 이모지는 char 2개(서로게이트 쌍)이지만 1자로 센다. char 수로 세면 11자가 되어 길이 사유가 나온다
    void countsLengthByCodePoint() {
        // given
        String nickname = "가나다라마바사아자\uD83D\uDE00";

        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(nickname.length()).isEqualTo(11);
        assertThat(messages).containsExactly(CHARACTER_SET_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㄱ", "-", "드롭 헌터 좋아요 반갑", "가나다라마바사아자차!"})
    @DisplayName("길이와 허용 문자를 함께 위반하면 길이 사유 하나만 보고한다")
    void reportsLengthBeforeCharacterSet(String nickname) {
        // when
        List<String> messages = validate(nickname);

        // then
        assertThat(messages).containsExactly(LENGTH_MESSAGE);
    }
}
