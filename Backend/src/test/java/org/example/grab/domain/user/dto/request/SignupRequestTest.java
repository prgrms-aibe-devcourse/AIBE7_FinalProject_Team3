package org.example.grab.domain.user.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidNickname;
import org.example.grab.domain.user.validation.ValidPassword;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SignupRequestTest {

    private static final String VALID_PASSWORD = "Password123!";
    private static final String VALID_NICKNAME = "드롭헌터";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeAll
    static void setUpValidator() {
        // Validation가 찾은 Hibernate Validator를 찾고 찾은 구현으로 ValidatorFactory를 만듦
        validatorFactory = Validation.buildDefaultValidatorFactory();
        // getValidator()를 사용하여 실제로 검사를 수행하는 Validator를 validator에 할당
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    // 어떤 제약이 위반됐는지를 비교한다. 문구는 아래 문구 테스트에서 따로 확인한다
    /*
    테스트에서 호출하는 validator.validate(new SignupRequest(...))를
    호출하면 필드의 제약 애노테이션을 훑고, 각 검증기의 isValid를 불러,
    위반 목록 -> 위반 제약 클래스 정보를 돌려준다.
     */
    private List<Class<? extends Annotation>> passwordViolations(String password) {
        return validator.validate(new SignupRequest(password, VALID_NICKNAME)).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("password"))
                .<Class<? extends Annotation>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .toList();
    }

    private List<Class<? extends Annotation>> nicknameViolations(String nickname) {
        return validator.validate(new SignupRequest(VALID_PASSWORD, nickname)).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("nickname"))
                .<Class<? extends Annotation>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "  드롭헌터  ",
            "\t드롭헌터\t",
            "\n드롭헌터\r\n",
            "\u3000드롭헌터\u3000"
    })
    @DisplayName("닉네임 앞뒤의 공백 문자를 제거한다")
    // 전각 스페이스(U+3000)는 trim()으로 제거되지 않으므로 strip()을 사용하는지 확인하는 테스트
    void stripsNicknameWhitespace(String nickname) {
        // when
        SignupRequest request = new SignupRequest("Password123!", nickname);

        // then
        assertThat(request.nickname()).isEqualTo("드롭헌터");
    }

    @Test
    @DisplayName("닉네임 중간의 공백은 삭제하지 않고 그대로 남긴다")
    // 중간 공백을 지우면 다른 닉네임으로 바뀌므로, 남겨 두고 이후 허용 문자 검증에서 INVALID_NICKNAME으로 거부하게 하는지 확인하는 테스트
    void keepsWhitespaceInsideNickname() {
        // when
        SignupRequest request = new SignupRequest("Password123!", " 드롭 헌터 ");

        // then
        assertThat(request.nickname()).isEqualTo("드롭 헌터");
    }

    @Test
    @DisplayName("닉네임이 null이면 예외 없이 null로 둔다")
    // null을 필수값 검증에서 VALIDATION_FAILED로 처리할 수 있도록 생성 단계에서 막지 않는지 확인하는 테스트
    void keepsNullNickname() {
        // when
        SignupRequest request = new SignupRequest("Password123!", null);

        // then
        assertThat(request.nickname()).isNull();
    }

    @Test
    @DisplayName("공백만 있는 닉네임은 빈 문자열이 된다")
    // 앞뒤 공백 제거 후 빈 값이 필수값 위반(VALIDATION_FAILED)으로 분류되도록 빈 문자열로 남는지 확인하는 테스트
    void stripsBlankNicknameToEmpty() {
        // when
        SignupRequest request = new SignupRequest("Password123!", " \t\u3000 ");

        // then
        assertThat(request.nickname()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  Password123!  ", "\tPassword123!", "Pass word123!"})
    @DisplayName("비밀번호는 공백을 포함해 전달받은 그대로 둔다")
    // 비밀번호를 trim하면 사용자가 입력한 값과 다른 값이 저장되므로 가공하지 않는지 확인하는 테스트
    void keepsOriginalPassword(String password) {
        // when
        SignupRequest request = new SignupRequest(password, "드롭헌터");

        // then
        assertThat(request.password()).isEqualTo(password);
    }

    @Test
    @DisplayName("JSON 바인딩에서도 닉네임 앞뒤 공백이 제거되고 비밀번호는 그대로 남는다")
    // 요청 본문 역직렬화가 정규 생성자를 거쳐, Bean Validation 전에 닉네임이 정리되는지 확인하는 테스트
    void stripsNicknameWhenDeserialized() {
        // given
        String body = """
                {"password": " Password123! ", "nickname": "\\u3000드롭헌터 "}
                """;

        // when
        SignupRequest request = jsonMapper.readValue(body, SignupRequest.class);

        // then
        assertThat(request.nickname()).isEqualTo("드롭헌터");
        assertThat(request.password()).isEqualTo(" Password123! ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password123!", "password1!", "Aa1!aaaa"})
    @DisplayName("비밀번호 규칙을 충족하면 비밀번호 위반이 없다")
    // 필수값 제약과 비밀번호 정책 제약이 DTO에 연결된 뒤에도 정상 값을 막지 않는지 확인하는 테스트
    void acceptsValidPassword(String password) {
        // when
        List<Class<? extends Annotation>> violations = passwordViolations(password);

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("비밀번호가 null이거나 빈 문자열이면 필수값 위반 하나만 나온다")
    // 누락·null·빈 문자열은 VALIDATION_FAILED로 분류되어야 하므로 비밀번호 정책 위반이 함께 나오지 않는지 확인하는 테스트
    void reportsOnlyRequiredViolation(String password) {
        // when
        List<Class<? extends Annotation>> violations = passwordViolations(password);

        // then
        assertThat(violations).containsExactly(NotEmpty.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"        ", "   ", "Pass word1!", " Password1! ", "Pa1!", "Password1", "비밀번호Pass1!"})
    @DisplayName("비밀번호 규칙을 위반하면 비밀번호 정책 위반 하나만 나온다")
    // 공백만 있는 값도 trim하지 않으므로 필수값이 아닌 비밀번호 규칙 위반(INVALID_PASSWORD)으로 분류되는지 확인하는 테스트
    void reportsOnlyPasswordPolicyViolation(String password) {
        // when
        List<Class<? extends Annotation>> violations = passwordViolations(password);

        // then
        assertThat(violations).containsExactly(ValidPassword.class);
    }

    @Test
    @DisplayName("비밀번호와 닉네임이 모두 유효하면 위반이 없다")
    void acceptsValidRequest() {
        // when
        var violations = validator.validate(new SignupRequest(VALID_PASSWORD, VALID_NICKNAME));

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {" 드롭 ", "\u3000드롭헌터\t", "\n가나다라마바사아자차\r\n"})
    @DisplayName("앞뒤 공백을 제거한 닉네임이 규칙을 충족하면 통과한다")
    // 길이는 앞뒤 공백을 제거한 값으로 센다. 공백을 포함하면 규칙을 벗어나는 값으로 생성자 정규화가 검증보다 먼저인지 확인한다
    void acceptsNicknameAfterStrip(String nickname) {
        // when
        List<Class<? extends Annotation>> violations = nicknameViolations(nickname);

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\u3000\t\n"})
    @DisplayName("닉네임이 null, 빈 문자열, 공백뿐이면 필수값 위반 하나만 나온다")
    // 공백만 있는 값은 생성자에서 빈 문자열이 되어 VALIDATION_FAILED로 분류되어야 한다(MEMBER_AUTH.md 1.2.3)
    void reportsOnlyRequiredViolationForNickname(String nickname) {
        // when
        List<Class<? extends Annotation>> violations = nicknameViolations(nickname);

        // then
        assertThat(violations).containsExactly(NotEmpty.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {" 드 ", "가나다라마바사아자차카", "드롭 헌터", "drop-hunter", "ㄱㄴ", "\u0001"})
    @DisplayName("닉네임 규칙을 위반하면 닉네임 규칙 위반 하나만 나온다")
    // 제어 문자(U+0001)는 strip()으로 제거되지 않아 1자로 남고, 필수값이 아닌 길이 사유로 거부되어야 한다
    void reportsOnlyNicknamePolicyViolation(String nickname) {
        // when
        List<Class<? extends Annotation>> violations = nicknameViolations(nickname);

        // then
        assertThat(violations).containsExactly(ValidNickname.class);
    }

    @Test
    @DisplayName("필수값 위반 문구는 필드별로 지정한 문구이며 실행 환경의 로케일에 따라 바뀌지 않는다")
    // @NotEmpty의 기본 문구는 로케일이 영어면 "must not be empty"가 되므로, 영어 로케일에서 만든 Validator로도 지정한 문구가 나오는지 확인하는 테스트
    void reportsFixedRequiredMessagesRegardlessOfLocale() {
        // given
        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        try (ValidatorFactory englishFactory = Validation.buildDefaultValidatorFactory()) {
            Validator englishValidator = englishFactory.getValidator();

            // when
            Map<String, String> messages = englishValidator.validate(new SignupRequest(null, "   ")).stream()
                    .collect(Collectors.toMap(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));

            // then
            assertThat(messages).containsOnly(
                    entry("password", "비밀번호는 필수입니다."),
                    entry("nickname", "닉네임은 필수입니다.")
            );
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Secret Pass1!     | 드롭헌터",
            "SecretPass한글1!  | 드롭헌터",
            "Sh0rt!            | 드롭헌터",
            "SecretPass1       | 드롭헌터",
            "Password123!      | Nick-Name",
            "Password123!      | 가나다라마바사아자차카",
            "Secret Pass1!     | Nick-Name"
    })
    @DisplayName("위반 문구에는 거부된 비밀번호·닉네임이 들어가지 않는다")
    // 문구는 fieldErrors[].reason으로 응답에 나가므로, 비밀번호 원문이나 입력값이 문구에 섞이지 않는지 확인하는 테스트
    void excludesRejectedValuesFromMessages(String password, String nickname) {
        // when
        List<String> messages = validator.validate(new SignupRequest(password, nickname)).stream()
                .map(ConstraintViolation::getMessage)
                .toList();

        // then
        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> assertThat(message)
                .isNotBlank()
                .doesNotContain(password)
                .doesNotContain(nickname));
    }

    @Test
    @DisplayName("toString은 비밀번호를 가리고 닉네임만 보여 준다")
    // record 기본 toString은 비밀번호 원문을 출력하므로, 로그 등에 남지 않도록 가렸는지 확인하는 테스트
    void masksPasswordInToString() {
        // given
        SignupRequest request = new SignupRequest("Secret#Pass123", "드롭헌터");

        // when
        String text = request.toString();

        // then
        assertThat(text)
                .isEqualTo("SignupRequest[password=masked, nickname=드롭헌터]")
                .doesNotContain("Secret#Pass123");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "a", "Password123!"})
    @DisplayName("toString은 비밀번호가 null이든 어떤 길이든 같은 값으로 가린다")
    // 가린 결과가 입력에 따라 달라지면 비밀번호 유무나 길이가 드러나므로, 항상 같은 문자열인지 확인하는 테스트
    void masksPasswordRegardlessOfValue(String password) {
        // when
        String text = new SignupRequest(password, "드롭헌터").toString();

        // then
        assertThat(text).isEqualTo("SignupRequest[password=masked, nickname=드롭헌터]");
    }
}
