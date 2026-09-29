package org.example.grab.domain.user.dto.request;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import org.example.grab.domain.user.error.UserConstraintErrorCodeMapping;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    회원가입 요청의 JSON 바인딩 → 정규화 → 검증 → 오류 변환 경로를 MockMvc로 확인한다(GR-28 M08).
    실제 회원가입 Controller·서비스는 GR-29·GR-30에서 만들므로, 받은 요청만 기록하는 테스트 전용 Controller를 쓴다.
    오류 코드 매핑·전역 예외 처리기는 운영과 같은 구성을 연결하고, 검증기는 standalone MockMvc의 기본 Bean Validation 검증기를 쓴다.
 */
class SignupRequestWebValidationTest {

    private static final String SIGNUP_PATH = "/test/signup";
    private static final String VALID_PASSWORD = "Password1!";
    private static final String VALID_NICKNAME = "드롭헌터";
    // 응답·로그 노출 검사용 비밀번호. 특수문자를 맨 뒤에 둬서, 따옴표 없는 값의 파싱 오류가 잘라 보여 주는 토큰(SECRET_TOKEN)도 비밀번호 대부분이 되게 한다
    private static final String SECRET_TOKEN = "SecretPw12";
    private static final String SECRET_PASSWORD = SECRET_TOKEN + "#";

    private static final String VALIDATION_FAILED_MESSAGE = "요청 값 검증에 실패했습니다.";
    private static final String INVALID_NICKNAME_MESSAGE = "닉네임이 규칙을 충족하지 않습니다.";
    private static final String INVALID_REQUEST_MESSAGE = "요청 형식이 올바르지 않습니다.";

    private static final String PASSWORD_REQUIRED_REASON = "비밀번호는 필수입니다.";
    private static final String NICKNAME_REQUIRED_REASON = "닉네임은 필수입니다.";
    private static final String NICKNAME_LENGTH_REASON = "닉네임은 2자 이상 10자 이하여야 합니다.";
    private static final String NICKNAME_CHARACTER_SET_REASON = "닉네임은 한글, 영문, 숫자, 밑줄(_)만 사용할 수 있습니다.";

    private static final String LENGTH_REASON = "비밀번호는 8자 이상 64자 이하여야 합니다.";
    private static final String WHITESPACE_REASON = "비밀번호에 공백을 포함할 수 없습니다.";
    private static final String CHARACTER_SET_REASON = "비밀번호는 영문, 숫자, 특수문자만 사용할 수 있습니다.";
    private static final String COMPOSITION_REASON = "비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.";

    private final TestSignupController controller = new TestSignupController();
    private MockMvc mockMvc;

    @RestController
    static class TestSignupController {

        private SignupRequest received;

        @PostMapping(SIGNUP_PATH)
        @ResponseStatus(HttpStatus.CREATED)
        ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
            this.received = request;
            return ApiResponse.success(null);
        }
    }

    @BeforeEach
    void setUp() {
        ValidationErrorCodeResolver resolver = new ValidationErrorCodeResolver(List.of(new UserConstraintErrorCodeMapping()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .build();
    }

    // 각 인자는 JSON 값 표현이다(문자열은 json()으로 감싼다). null이면 해당 키를 본문에서 뺀다
    private static String body(String passwordJson, String nicknameJson) {
        List<String> members = new ArrayList<>();
        if (passwordJson != null) {
            members.add("\"password\":" + passwordJson);
        }
        if (nicknameJson != null) {
            members.add("\"nickname\":" + nicknameJson);
        }
        return "{" + String.join(",", members) + "}";
    }

    // 따옴표·역슬래시처럼 JSON 이스케이프가 필요한 문자는 호출하는 쪽에서 이스케이프해 넘긴다
    private static String json(String value) {
        return "\"" + value + "\"";
    }

    private ResultActions postSignup(String body) throws Exception {
        return mockMvc.perform(post(SIGNUP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("유효한 요청은 실제 SignupRequest로 바인딩·정규화되어 Controller에 도달한다")
    void bindsValidRequestToSignupRequest() throws Exception {
        // when
        postSignup("{\"password\":\"Password1!\",\"nickname\":\"  드롭헌터  \"}")
                .andExpect(status().isCreated());

        // then
        assertThat(controller.received).isNotNull();
        assertThat(controller.received.password()).isEqualTo("Password1!");
        assertThat(controller.received.nickname()).isEqualTo("드롭헌터");
    }

    @Test
    @DisplayName("phone 없는 유효 요청은 검증을 통과하고 공통 성공 응답으로 직렬화된다")
    // 테스트 전용 Controller의 응답이므로 DB 회원 생성 완료를 의미하지 않는다. 실제 응답 data(userId 등)는 GR-29·GR-30에서 확인한다
    void respondsWithCommonSuccessForValidRequestWithoutPhone() throws Exception {
        // when & then
        postSignup("{\"password\":\"Password1!\",\"nickname\":\"드롭헌터\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(controller.received).isEqualTo(new SignupRequest("Password1!", "드롭헌터"));
    }

    @Test
    @DisplayName("검증에 실패한 요청은 Controller에 도달하지 않고 전역 예외 처리기가 회원가입 오류 코드로 응답한다")
    // user 도메인 매핑이 연결되지 않으면 INVALID_PASSWORD 대신 VALIDATION_FAILED가 나온다
    void handlesInvalidRequestWithGlobalExceptionHandler() throws Exception {
        // when
        postSignup("{\"password\":\"short\",\"nickname\":\"드롭헌터\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));

        // then
        assertThat(controller.received).isNull();
    }

    // 첫 값은 JSON 문자열 안에 그대로 넣는 표현이다(탭은 JSON 이스케이프 \t로 적는다). 규칙별 경계값은 PasswordValidatorTest가 다룬다
    static Stream<Arguments> passwordPolicyViolations() {
        return Stream.of(
                Arguments.of("Pass1!a", LENGTH_REASON),
                Arguments.of("Password1!" + "a".repeat(55), LENGTH_REASON),
                Arguments.of("Pass word1!", WHITESPACE_REASON),
                Arguments.of("Pass\\tword1!", WHITESPACE_REASON),
                // 비밀번호는 trim하지 않으므로 공백만 있는 값은 필수값 위반이 아니라 비밀번호 규칙 위반이다(M00-02)
                Arguments.of("        ", WHITESPACE_REASON),
                Arguments.of("Password1!비밀", CHARACTER_SET_REASON),
                Arguments.of("Password1", COMPOSITION_REASON)
        );
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("passwordPolicyViolations")
    @DisplayName("비밀번호 정책만 위반하면 400, INVALID_PASSWORD, password 필드 오류 하나로 응답한다")
    void respondsWithInvalidPasswordForPasswordPolicyViolation(String jsonPassword, String expectedReason) throws Exception {
        // when & then
        postSignup("{\"password\":\"" + jsonPassword + "\",\"nickname\":\"" + VALID_NICKNAME + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.error.message").value("비밀번호가 규칙을 충족하지 않습니다."))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value(expectedReason));

        assertThat(controller.received).isNull();
    }

    // M08-05. 필수값 누락: 키 누락·null·빈 문자열, 닉네임은 앞뒤 공백 제거 후 빈 값까지 VALIDATION_FAILED다(M00-02)
    static Stream<Arguments> requiredFieldViolations() {
        return Stream.of(
                Arguments.of("비밀번호 키 누락", body(null, json(VALID_NICKNAME)), "password", PASSWORD_REQUIRED_REASON),
                Arguments.of("비밀번호 null", body("null", json(VALID_NICKNAME)), "password", PASSWORD_REQUIRED_REASON),
                Arguments.of("비밀번호 빈 문자열", body(json(""), json(VALID_NICKNAME)), "password", PASSWORD_REQUIRED_REASON),
                Arguments.of("닉네임 키 누락", body(json(VALID_PASSWORD), null), "nickname", NICKNAME_REQUIRED_REASON),
                Arguments.of("닉네임 null", body(json(VALID_PASSWORD), "null"), "nickname", NICKNAME_REQUIRED_REASON),
                Arguments.of("닉네임 빈 문자열", body(json(VALID_PASSWORD), json("")), "nickname", NICKNAME_REQUIRED_REASON),
                Arguments.of("닉네임 스페이스만", body(json(VALID_PASSWORD), json("   ")), "nickname", NICKNAME_REQUIRED_REASON),
                Arguments.of("닉네임 탭·스페이스만", body(json(VALID_PASSWORD), json(" \\t ")), "nickname", NICKNAME_REQUIRED_REASON),
                Arguments.of("닉네임 전각 스페이스만", body(json(VALID_PASSWORD), json("　　")), "nickname", NICKNAME_REQUIRED_REASON)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("requiredFieldViolations")
    @DisplayName("필수값이 없으면 400, VALIDATION_FAILED, 해당 필드의 필수값 사유 하나로 응답한다")
    void respondsWithValidationFailedForMissingRequiredField(
            String description, String body, String expectedField, String expectedReason) throws Exception {
        // when & then
        postSignup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value(VALIDATION_FAILED_MESSAGE))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value(expectedField))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value(expectedReason));

        assertThat(controller.received).isNull();
    }

    // M08-05. 닉네임 규칙: 앞뒤 공백 제거 후 코드 포인트 2~10자, 한글(완성형)·영문·숫자·밑줄만 허용한다
    static Stream<Arguments> nicknamePolicyViolations() {
        return Stream.of(
                Arguments.of("1자", "드", NICKNAME_LENGTH_REASON),
                Arguments.of("11자", "가".repeat(11), NICKNAME_LENGTH_REASON),
                Arguments.of("앞뒤 공백 제거 후 1자", " 드 ", NICKNAME_LENGTH_REASON),
                Arguments.of("중간 공백", "드롭 헌터", NICKNAME_CHARACTER_SET_REASON),
                Arguments.of("허용 외 특수문자", "드롭-헌터", NICKNAME_CHARACTER_SET_REASON),
                Arguments.of("자모만 있는 한글", "ㄱㄴ", NICKNAME_CHARACTER_SET_REASON),
                Arguments.of("이모지", "드롭😀", NICKNAME_CHARACTER_SET_REASON)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nicknamePolicyViolations")
    @DisplayName("닉네임 규칙만 위반하면 400, INVALID_NICKNAME, nickname 필드 오류 하나로 응답한다")
    void respondsWithInvalidNicknameForNicknamePolicyViolation(
            String description, String nickname, String expectedReason) throws Exception {
        // when & then
        postSignup(body(json(VALID_PASSWORD), json(nickname)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_NICKNAME"))
                .andExpect(jsonPath("$.error.message").value(INVALID_NICKNAME_MESSAGE))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value(expectedReason));

        assertThat(controller.received).isNull();
    }

    // M08-06. 여러 필드 동시 위반: 필드마다 사유 하나, password → nickname 순서, 코드가 섞이면 VALIDATION_FAILED(M00-05, T09)
    static Stream<Arguments> multipleFieldViolations() {
        return Stream.of(
                Arguments.of("비밀번호 문자 조합 + 닉네임 허용 문자",
                        body(json("Password1"), json("드롭-헌터")), COMPOSITION_REASON, NICKNAME_CHARACTER_SET_REASON),
                Arguments.of("비밀번호 누락 + 닉네임 누락",
                        "{}", PASSWORD_REQUIRED_REASON, NICKNAME_REQUIRED_REASON),
                Arguments.of("비밀번호 길이 + 닉네임 누락",
                        body(json("Pass1!a"), null), LENGTH_REASON, NICKNAME_REQUIRED_REASON),
                Arguments.of("비밀번호 null + 닉네임 길이",
                        body("null", json("드")), PASSWORD_REQUIRED_REASON, NICKNAME_LENGTH_REASON),
                Arguments.of("비밀번호 공백만 + 닉네임 공백만",
                        body(json("        "), json("   ")), WHITESPACE_REASON, NICKNAME_REQUIRED_REASON),
                // 본문의 키 순서와 관계없이 password → nickname 순서로 담는다
                Arguments.of("본문에서 닉네임을 먼저 적은 경우",
                        "{\"nickname\":\"드롭-헌터\",\"password\":\"Password1\"}", COMPOSITION_REASON, NICKNAME_CHARACTER_SET_REASON)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("multipleFieldViolations")
    @DisplayName("여러 필드가 함께 잘못되면 VALIDATION_FAILED와 password, nickname 순서의 사유를 누락 없이 담는다")
    void respondsWithAllFieldReasonsForMultipleViolations(
            String description, String body, String passwordReason, String nicknameReason) throws Exception {
        // when & then
        postSignup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value(VALIDATION_FAILED_MESSAGE))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value(passwordReason))
                .andExpect(jsonPath("$.error.fieldErrors[1].field").value("nickname"))
                .andExpect(jsonPath("$.error.fieldErrors[1].reason").value(nicknameReason));

        assertThat(controller.received).isNull();
    }

    // M08-07. 요청 본문 오류: 본문이 없거나 JSON으로 해석할 수 없으면 INVALID_REQUEST, fieldErrors는 빈 배열이다(MEMBER_AUTH.md 1.2.3)
    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of("빈 본문", ""),
                Arguments.of("공백만 있는 본문", "   "),
                Arguments.of("닫히지 않은 JSON", "{\"password\":\"" + SECRET_PASSWORD + "\",\"nickname\":\"드롭헌터\""),
                Arguments.of("따옴표 없는 값", "{\"password\":" + SECRET_PASSWORD + ",\"nickname\":\"드롭헌터\"}"),
                Arguments.of("객체가 아닌 배열", "[\"" + SECRET_PASSWORD + "\"]"),
                Arguments.of("문자열 필드에 객체", "{\"password\":{\"value\":\"" + SECRET_PASSWORD + "\"},\"nickname\":\"드롭헌터\"}")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unreadableBodies")
    @DisplayName("본문이 없거나 JSON으로 해석할 수 없으면 400, INVALID_REQUEST, 빈 fieldErrors의 공통 오류 구조로 응답한다")
    void respondsWithInvalidRequestForUnreadableBody(String description, String body) throws Exception {
        // when & then
        postSignup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.length()").value(3))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(INVALID_REQUEST_MESSAGE))
                .andExpect(jsonPath("$.error.fieldErrors").isArray())
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString(SECRET_TOKEN))));

        assertThat(controller.received).isNull();
    }

    // M08-08. 비밀번호 원문이 섞인 요청: 성공과 아래 실패 요청을 모두 다룬다
    static Stream<Arguments> requestsContainingSecretPassword() {
        return Stream.concat(
                Stream.of(Arguments.of("유효한 요청", body(json(SECRET_PASSWORD), json(VALID_NICKNAME)))),
                invalidRequestsContainingSecretPassword());
    }

    // M08-08·09. 비밀번호 원문이 섞인 실패 요청: 규칙 위반, 다중 위반, 본문 해석 실패
    static Stream<Arguments> invalidRequestsContainingSecretPassword() {
        return Stream.of(
                Arguments.of("비밀번호 공백 위반", body(json(SECRET_PASSWORD + " "), json(VALID_NICKNAME))),
                Arguments.of("비밀번호 허용 문자 위반", body(json(SECRET_PASSWORD + "한"), json(VALID_NICKNAME))),
                Arguments.of("비밀번호 길이 위반", body(json(SECRET_PASSWORD + "a".repeat(60)), json(VALID_NICKNAME))),
                Arguments.of("비밀번호 유효 + 닉네임 위반", body(json(SECRET_PASSWORD), json("드롭-헌터"))),
                Arguments.of("비밀번호·닉네임 동시 위반", body(json(SECRET_PASSWORD + " "), json("드"))),
                Arguments.of("닫히지 않은 JSON", "{\"password\":\"" + SECRET_PASSWORD + "\",\"nickname\":\"드롭헌터\""),
                Arguments.of("따옴표 없는 값", "{\"password\":" + SECRET_PASSWORD + ",\"nickname\":\"드롭헌터\"}"),
                Arguments.of("닉네임 타입 불일치", "{\"password\":\"" + SECRET_PASSWORD + "\",\"nickname\":{\"value\":1}}")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("requestsContainingSecretPassword")
    @DisplayName("응답 본문에 비밀번호 원문과 비밀번호 해시 필드가 없다")
    // 테스트 전용 Controller는 data를 비워 응답하므로, 실제 회원가입 응답 DTO의 해시 미포함은 GR-29·GR-30에서 확인한다
    void excludesPasswordAndHashFromResponse(String description, String body) throws Exception {
        // when & then
        postSignup(body)
                .andExpect(content().string(not(containsString(SECRET_TOKEN))))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("password_hash"))));
    }

    /*
        운영 로그 레벨(org.springframework INFO)에서 검증·파싱 실패 로그에 비밀번호가 없는지 확인한다.
        org.springframework를 DEBUG로 올리면 Spring 내부 로그가 검증 예외의 rejected value와 JSON 파싱 오류 토큰을 기록해
        비밀번호가 남을 수 있다. 이 경로는 코드로 막지 않고 운영에서 프레임워크 DEBUG를 켜지 않는 정책으로 다룬다(GR-28 M07-07).
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidRequestsContainingSecretPassword")
    @DisplayName("운영 로그 레벨에서 검증·파싱 실패 로그에 비밀번호 원문이 없다")
    void doesNotLogPassword(String description, String body) throws Exception {
        // given
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger springLogger = (Logger) LoggerFactory.getLogger("org.springframework");
        Level previousSpringLevel = springLogger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
        springLogger.setLevel(Level.INFO);

        // when
        try {
            postSignup(body);
        } finally {
            rootLogger.detachAppender(logAppender);
            springLogger.setLevel(previousSpringLevel);
        }

        // then
        // 전역 예외 처리기의 실패 로그가 실제로 캡처됐는지 함께 확인해, 로그가 없어서 통과하는 경우를 막는다
        assertThat(logAppender.list)
                .anyMatch(event -> event.getLoggerName().equals(GlobalExceptionHandler.class.getName()));
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(SECRET_TOKEN));
    }
}
