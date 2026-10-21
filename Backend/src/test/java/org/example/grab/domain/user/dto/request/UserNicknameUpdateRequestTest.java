package org.example.grab.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidNickname;
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

// GR-129 M05-01: 닉네임 수정 요청은 가입(SignupRequest)과 같은 닉네임 규칙·앞뒤 공백 제거를 쓴다(MEMBER_AUTH.md 1.10)
class UserNicknameUpdateRequestTest {

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

    // 어떤 제약이 위반됐는지를 비교한다(SignupRequestTest와 같은 방식)
    private List<Class<? extends Annotation>> violations(String nickname) {
        return validator.validate(new UserNicknameUpdateRequest(nickname)).stream()
                .<Class<? extends Annotation>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  드롭헌터  ", "\t드롭헌터\n", "　드롭헌터　"})
    @DisplayName("닉네임 앞뒤의 공백 문자(전각 스페이스 포함)를 제거한다")
    void stripsNicknameWhitespace(String nickname) {
        assertThat(new UserNicknameUpdateRequest(nickname).nickname()).isEqualTo("드롭헌터");
    }

    @Test
    @DisplayName("닉네임이 null이면 예외 없이 null로 둔다")
    // 필수값 검증에서 VALIDATION_FAILED로 처리하도록 생성 단계에서 막지 않는다
    void keepsNullNickname() {
        assertThat(new UserNicknameUpdateRequest(null).nickname()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "　"})
    @DisplayName("닉네임이 없거나 공백뿐이면 필수값 위반 하나만 보고한다")
    void rejectsMissingNickname(String nickname) {
        assertThat(violations(nickname)).containsExactly(NotEmpty.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "열한글자닉네임입니다요", "드롭 헌터", "drop-hunter", "닉네임!"})
    @DisplayName("길이·허용 문자 규칙을 어기면 닉네임 규칙 위반이다")
    void rejectsInvalidNickname(String nickname) {
        assertThat(violations(nickname)).containsExactly(ValidNickname.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"드롭헌터", "Grab_2", "  ab  ", "열글자닉네임입니다"})
    @DisplayName("규칙에 맞는 닉네임은 위반이 없다")
    void acceptsValidNickname(String nickname) {
        assertThat(violations(nickname)).isEmpty();
    }
}
