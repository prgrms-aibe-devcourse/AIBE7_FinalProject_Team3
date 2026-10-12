package org.example.grab.domain.user.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.example.grab.domain.user.support.EmailSignupTokenCookie;
import org.example.grab.domain.user.support.EmailSignupTokenGenerator;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-30 M02-01: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL·Redis로 로그인 없는 회원가입 API를 확인한다.
    가입 컨텍스트는 인증 코드 확인(GR-61)을 마친 상태처럼 저장소에 직접 만들고, 요청에는 access_token 쿠키를 붙이지 않는다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일·닉네임을 쓰고, 만든 회원을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class SignupApiIntegrationTest {

    private static final String SIGNUP_PATH = "/api/v1/auth/signup";
    private static final String PASSWORD = "Password123!";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailSignupContextRepository signupContextRepository;

    @Autowired
    private EmailSignupTokenGenerator tokenGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 테스트가 쓴 이메일·닉네임의 공통 접두어. 다른 테스트의 데이터와 섞이지 않게 한다
    private final String run = UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "signup-api-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "signup-api-" + run + "-" + name + "@example.com";
    }

    // 닉네임 규칙(2~10자)에 맞춰 접두어는 4자 이하로 쓴다
    private String nickname(String prefix) {
        return prefix + run;
    }

    private String issueContext(String email) {
        String token = tokenGenerator.generate();
        signupContextRepository.save(token, email);
        return token;
    }

    private static String body(String password, String nickname) {
        return "{\"password\":\"" + password + "\",\"nickname\":\"" + nickname + "\"}";
    }

    private static MockHttpServletRequestBuilder signupRequest(String body) {
        // 실제 필터 체인이라 CSRF 토큰이 필요하다(GR-44). CSRF 동작 자체는 CsrfProtectionTests가 확인한다
        return post(SIGNUP_PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions signup(String token, String body) throws Exception {
        return mockMvc.perform(signupRequest(body).cookie(new Cookie(EmailSignupTokenCookie.NAME, token)));
    }

    private int countUsersByEmail(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    @Test
    @DisplayName("로그인 없이 유효한 가입 컨텍스트 쿠키로 가입하면 201, 명세의 응답 필드, 쿠키 만료 헤더를 반환하고 회원 1명이 저장된다")
    void signsUpWithoutLogin() throws Exception {
        // given
        String email = email("ok");
        String token = issueContext(email);

        // when
        ResultActions result = signup(token, body(PASSWORD, nickname("Ok")))
                .andExpect(status().isCreated());

        // then: 응답
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT public_id, role, created_at FROM users WHERE email = ?", email);
        result.andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(row.get("public_id").toString()))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.nickname").value(nickname("Ok")))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString(token))))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString(EmailSignupTokenCookie.NAME + "=;"),
                        containsString("Path=" + EmailSignupTokenCookie.PATH),
                        containsString("Max-Age=0"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"))));

        // then: createdAt은 ISO 8601 문자열이고 DB 저장값과 같은 시각이다
        String createdAt = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.createdAt");
        assertThat(OffsetDateTime.parse(createdAt))
                .isEqualTo(jdbcTemplate.queryForObject("SELECT created_at FROM users WHERE email = ?", OffsetDateTime.class, email));

        // then: 저장
        assertThat(countUsersByEmail(email)).isEqualTo(1);
        assertThat(row).containsEntry("role", "USER");
    }

    @Test
    @DisplayName("본문에 역할·이메일을 넣어도 무시되어 USER로, 가입 컨텍스트의 이메일로 저장된다")
    void ignoresRoleAndEmailInBody() throws Exception {
        // given
        String email = email("role");
        String token = issueContext(email);
        String body = "{\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + nickname("Role") + "\","
                + "\"role\":\"ADMIN\",\"roles\":[\"ADMIN\",\"SELLER\"],\"email\":\"" + email("other") + "\"}";

        // when & then
        signup(token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email))
                .isEqualTo("USER");
        assertThat(countUsersByEmail(email("other"))).isZero();
    }

    @Test
    @DisplayName("가입 컨텍스트가 없으면 본문이 잘못돼도 400이 아니라 401 EMAIL_SIGNUP_CONTEXT_INVALID")
    void checksContextBeforeBody() throws Exception {
        // 쿠키 없음
        mockMvc.perform(signupRequest(body("abc", "a")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SIGNUP_CONTEXT_INVALID"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        // 알 수 없는(만료돼 삭제된 경우와 같은) 토큰
        signup(tokenGenerator.generate(), body("abc", "a"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SIGNUP_CONTEXT_INVALID"));
    }

    @Test
    @DisplayName("가입에 성공한 컨텍스트 쿠키로 다시 요청하면 401 EMAIL_SIGNUP_CONTEXT_INVALID")
    void rejectsConsumedContext() throws Exception {
        // given
        String email = email("used");
        String token = issueContext(email);
        signup(token, body(PASSWORD, nickname("Used"))).andExpect(status().isCreated());

        // when & then
        signup(token, body(PASSWORD, nickname("Agn")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SIGNUP_CONTEXT_INVALID"));
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 값 오류는 400이고 컨텍스트를 소비하지 않아 같은 쿠키로 다시 가입할 수 있다")
    void keepsContextAfterInvalidValues() throws Exception {
        // given
        String email = email("inv");
        String token = issueContext(email);

        // when & then
        signup(token, body("abc", nickname("Inv")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        signup(token, body(PASSWORD, "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_NICKNAME"));
        signup(token, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.error.fieldErrors[1].field").value("nickname"));
        assertThat(countUsersByEmail(email)).isZero();

        signup(token, body(PASSWORD, nickname("Inv")))
                .andExpect(status().isCreated());
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 가입된 이메일의 컨텍스트는 409 DUPLICATE_EMAIL이고 쿠키 만료 헤더가 없다")
    void rejectsDuplicateEmail() throws Exception {
        // given
        String email = email("dup");
        signup(issueContext(email), body(PASSWORD, nickname("Dup"))).andExpect(status().isCreated());

        // when & then
        signup(issueContext(email), body(PASSWORD, nickname("Dup2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임은 409 DUPLICATE_NICKNAME이고 같은 쿠키로 다른 닉네임이면 가입된다")
    void rejectsDuplicateNicknameAndAllowsRetry() throws Exception {
        // given
        signup(issueContext(email("first")), body(PASSWORD, nickname("Nick"))).andExpect(status().isCreated());
        String email = email("second");
        String token = issueContext(email);

        // when & then
        signup(token, body(PASSWORD, nickname("NICK")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_NICKNAME"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        signup(token, body(PASSWORD, nickname("Oth")))
                .andExpect(status().isCreated());
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("만료·변조된 access_token 쿠키가 남아 있어도 토큰 검증 401 없이 가입된다")
    void ignoresInvalidAccessTokenCookie() throws Exception {
        // given
        String email = email("tok");
        String token = issueContext(email);

        // when & then
        mockMvc.perform(signupRequest(body(PASSWORD, nickname("Tok")))
                        .cookie(new Cookie(EmailSignupTokenCookie.NAME, token), new Cookie("access_token", "not-a-jwt")))
                .andExpect(status().isCreated());
    }
}
