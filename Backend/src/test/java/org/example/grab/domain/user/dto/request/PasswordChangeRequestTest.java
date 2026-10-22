package org.example.grab.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidPassword;
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

// GR-129 M05-01: 현재 비밀번호는 필수값만, 새 비밀번호는 가입과 같은 규칙으로 검사한다(MEMBER_AUTH.md 1.11, M00-04)
class PasswordChangeRequestTest {

    private static final String VALID_PASSWORD = "Password123!";

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

    private List<Class<? extends Annotation>> violations(PasswordChangeRequest request, String property) {
        return validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals(property))
                .<Class<? extends Annotation>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .toList();
    }

    @Test
    @DisplayName("두 비밀번호 모두 가공하지 않는다")
    void keepsPasswordsAsIs() {
        // when
        PasswordChangeRequest request = new PasswordChangeRequest(" Password123! ", " NewPassword456! ");

        // then
        assertThat(request.currentPassword()).isEqualTo(" Password123! ");
        assertThat(request.newPassword()).isEqualTo(" NewPassword456! ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("현재 비밀번호가 없거나 빈 문자열이면 필수값 위반이다")
    void rejectsMissingCurrentPassword(String currentPassword) {
        assertThat(violations(new PasswordChangeRequest(currentPassword, VALID_PASSWORD), "currentPassword"))
                .containsExactly(NotEmpty.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "   ", "nodigits!!!", "비밀번호123!"})
    @DisplayName("현재 비밀번호는 비밀번호 규칙을 검사하지 않는다(규칙이 바뀌어도 기존 비밀번호로 변경할 수 있다)")
    void doesNotValidateCurrentPasswordRules(String currentPassword) {
        assertThat(violations(new PasswordChangeRequest(currentPassword, VALID_PASSWORD), "currentPassword")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("새 비밀번호가 없거나 빈 문자열이면 필수값 위반 하나만 보고한다")
    void rejectsMissingNewPassword(String newPassword) {
        assertThat(violations(new PasswordChangeRequest(VALID_PASSWORD, newPassword), "newPassword"))
                .containsExactly(NotEmpty.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pass1!", "   ", "Password123", "Password!!!", "Pass word123!", "비밀번호Password123!"})
    @DisplayName("새 비밀번호가 규칙을 어기면(공백뿐인 값 포함) 비밀번호 규칙 위반이다")
    void rejectsInvalidNewPassword(String newPassword) {
        assertThat(violations(new PasswordChangeRequest(VALID_PASSWORD, newPassword), "newPassword"))
                .containsExactly(ValidPassword.class);
    }

    @Test
    @DisplayName("규칙에 맞는 요청은 위반이 없다")
    void acceptsValidRequest() {
        assertThat(validator.validate(new PasswordChangeRequest(VALID_PASSWORD, "NewPassword456!"))).isEmpty();
    }

    @Test
    @DisplayName("toString은 두 비밀번호를 가린다")
    void masksPasswordsInToString() {
        // when
        String text = new PasswordChangeRequest("Password123!", "NewPassword456!").toString();

        // then
        assertThat(text).doesNotContain("Password123!", "NewPassword456!").contains("masked");
    }
}
