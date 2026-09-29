package org.example.grab.domain.user.error;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.error.ValidationFailure;
import org.example.grab.global.validation.SensitiveValueMaskingValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UserConstraintErrorCodeMappingTest {

    private static final String VALID_PASSWORD = "Password1!";
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
        return resolveFieldError(new SignupRequest(password, VALID_NICKNAME), "password");
    }

    private ErrorCode resolveNicknameError(String nickname) {
        return resolveFieldError(new SignupRequest(VALID_PASSWORD, nickname), "nickname");
    }

    private ErrorCode resolveFieldError(SignupRequest request, String field) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, "signupRequest");
        springValidator.validate(request, result);

        List<FieldError> fieldErrors = result.getFieldErrors(field);
        assertThat(fieldErrors).hasSize(1);
        return resolver.resolve(fieldErrors.get(0));
    }

    private ValidationFailure resolveFailure(SignupRequest request) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, "signupRequest");
        springValidator.validate(request, result);
        return resolver.resolve(result);
    }

    // 응답에 나갈 값(최상위 코드, 필드 이름과 사유)만 비교한다
    private List<String> summarize(ValidationFailure failure) {
        return Stream.concat(
                Stream.of(failure.errorCode().getCode()),
                failure.fieldErrors().stream().map(error -> error.getField() + ":" + error.getDefaultMessage())
        ).toList();
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

    @ParameterizedTest
    @ValueSource(strings = {"드", "가나다라마바사아자차카", "드롭 헌터", "drop!", "ㄷㄹ헌터"})
    @DisplayName("닉네임 길이·허용 문자 위반은 INVALID_NICKNAME으로 연결한다")
    // 1자·11자(길이), 중간 공백·특수문자·자모(허용 문자) 중 어느 사유로 거부돼도 같은 오류 코드가 나오는지 확인하는 테스트
    void resolvesNicknamePolicyViolationToInvalidNickname(String nickname) {
        // when
        ErrorCode errorCode = resolveNicknameError(nickname);

        // then
        assertThat(errorCode).isEqualTo(UserErrorCode.INVALID_NICKNAME);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "　"})
    @DisplayName("닉네임 필수값 위반은 INVALID_NICKNAME이 아닌 VALIDATION_FAILED로 둔다")
    // 누락·null·빈 문자열과 앞뒤 공백 제거 후 빈 값은 필수값 위반으로 분류한다(MEMBER_AUTH.md 1.2.3, GR-28 M00-02)
    void resolvesNicknameRequiredViolationToValidationFailed(String nickname) {
        // when
        ErrorCode errorCode = resolveNicknameError(nickname);

        // then
        assertThat(errorCode).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    static Stream<Arguments> multipleViolations() {
        return Stream.of(
                Arguments.of("비밀번호 길이 + 닉네임 유효", "Pa1!", VALID_NICKNAME,
                        UserErrorCode.INVALID_PASSWORD, List.of("password")),
                Arguments.of("닉네임 길이 + 비밀번호 유효", VALID_PASSWORD, "드",
                        UserErrorCode.INVALID_NICKNAME, List.of("nickname")),
                Arguments.of("비밀번호 문자 조합 + 닉네임 허용 문자", "Password1", "드롭 헌터",
                        CommonErrorCode.VALIDATION_FAILED, List.of("password", "nickname")),
                Arguments.of("비밀번호 누락 + 닉네임 누락", null, null,
                        CommonErrorCode.VALIDATION_FAILED, List.of("password", "nickname")),
                Arguments.of("비밀번호 길이 + 닉네임 누락", "Pa1!", null,
                        CommonErrorCode.VALIDATION_FAILED, List.of("password", "nickname"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipleViolations")
    @DisplayName("다중 위반의 최상위 코드와 필드 오류 순서는 명세를 따른다")
    // 코드가 모두 같으면 그 코드, 섞이면 VALIDATION_FAILED이고 fieldErrors는 password → nickname 순서다(MEMBER_AUTH.md 1.2.3, GR-28 T09)
    void resolvesMultipleViolations(String description, String password, String nickname,
                                    ErrorCode expectedCode, List<String> expectedFields) {
        // when
        ValidationFailure failure = resolveFailure(new SignupRequest(password, nickname));

        // then
        assertThat(failure.errorCode()).isEqualTo(expectedCode);
        assertThat(failure.fieldErrors()).extracting(FieldError::getField).containsExactlyElementsOf(expectedFields);
    }

    @Test
    @DisplayName("같은 요청을 반복 검증해도 같은 코드와 필드 오류 목록이 나온다")
    // Bean Validation의 위반 보고 순서에 기대지 않고 결과가 고정되는지 확인하는 테스트(GR-28 M06-08)
    void resolvesSameResultForRepeatedValidation() {
        // given
        SignupRequest request = new SignupRequest("Password1", "드롭 헌터");
        List<String> first = summarize(resolveFailure(request));

        // when
        List<List<String>> repeated = IntStream.range(0, 50)
                .mapToObj(i -> summarize(resolveFailure(request)))
                .toList();

        // then
        assertThat(first).hasSize(3).startsWith("VALIDATION_FAILED");
        assertThat(repeated).allSatisfy(result -> assertThat(result).isEqualTo(first));
    }

    @Test
    @DisplayName("비밀번호의 거부된 값은 가려도 INVALID_PASSWORD로 분류하고, 닉네임은 가리지 않는다")
    // 운영 검증기는 @SensitiveValue 필드의 rejectedValue를 가린다(GR-28 M07-07). 오류 코드 선택이 원본 위반으로 유지되는지 확인한다
    void masksPasswordWithoutChangingErrorCode() {
        // given
        SensitiveValueMaskingValidator maskingValidator = new SensitiveValueMaskingValidator();
        maskingValidator.afterPropertiesSet();
        SignupRequest request = new SignupRequest("short1!", "a");
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, "signupRequest");

        // when
        try {
            maskingValidator.validate(request, result);
        } finally {
            maskingValidator.close();
        }

        // then
        FieldError passwordError = result.getFieldError("password");
        assertThat(passwordError.getRejectedValue()).isEqualTo(SensitiveValueMaskingValidator.MASKED_VALUE);
        assertThat(passwordError.toString()).doesNotContain("short1!");
        assertThat(resolver.resolve(passwordError)).isEqualTo(UserErrorCode.INVALID_PASSWORD);
        assertThat(result.getFieldError("nickname").getRejectedValue()).isEqualTo("a");
    }
}
