package org.example.grab.domain.user.error;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserConstraintErrorCodeMappingTest {

    private static final String VALID_NICKNAME = "드롭헌터";

    private static ValidatorFactory validatorFactory;
    private static SpringValidatorAdapter springValidator;

    private final ValidationErrorCodeResolver resolver =
            new ValidationErrorCodeResolver(List.of(new UserConstraintErrorCodeMapping()));

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

    private ErrorCode resolvePasswordError(String password) {
        SignupRequest request = new SignupRequest(password, VALID_NICKNAME);
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, "signupRequest");
        springValidator.validate(request, result);

        List<FieldError> passwordErrors = result.getFieldErrors("password");
        assertThat(passwordErrors).hasSize(1);
        return resolver.resolve(passwordErrors.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pa1!", "Pass word1!", "비밀번호Pass1!", "Password1", "        "})
    @DisplayName("비밀번호 정책 위반은 INVALID_PASSWORD로 연결한다")
    // 길이·공백·허용 문자·문자 조합 중 어느 사유로 거부돼도 같은 오류 코드가 나오는지 확인하는 테스트
    void resolvesPasswordPolicyViolationToInvalidPassword(String password) {
        // when
        ErrorCode errorCode = resolvePasswordError(password);

        // then
        assertThat(errorCode).isEqualTo(UserErrorCode.INVALID_PASSWORD);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("비밀번호 필수값 위반은 INVALID_PASSWORD가 아닌 VALIDATION_FAILED로 둔다")
    // 누락·null·빈 문자열은 필수값 위반으로 분류한다(MEMBER_AUTH.md 1.2.3, GR-28 M00-02)
    void resolvesPasswordRequiredViolationToValidationFailed(String password) {
        // when
        ErrorCode errorCode = resolvePasswordError(password);

        // then
        assertThat(errorCode).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }
}
