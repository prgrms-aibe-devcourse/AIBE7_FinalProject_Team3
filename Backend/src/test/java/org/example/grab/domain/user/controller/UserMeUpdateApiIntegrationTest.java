package org.example.grab.domain.user.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.JwtProvider;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-129 M05-04: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL·Redis로 내 닉네임 수정(1.10)·비밀번호 변경(1.11)을 확인한다.
    로그인 API로 받은 토큰을 쓰고, 세션 유지(M00-03)는 변경 전에 받은 Refresh Token으로 재발급해 확인한다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일·닉네임으로 회원을 만들고 직접 지운다(UserMeApiIntegrationTest와 같음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class UserMeUpdateApiIntegrationTest {

    private static final String ME_PATH = "/api/v1/users/me";
    private static final String PASSWORD_PATH = "/api/v1/users/me/password";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "Password123!";
    private static final String NEW_PASSWORD = "NewPassword456!";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtProvider jwtProvider;

    // 닉네임은 10자 이하 영문·숫자라 "u" + 실행마다 다른 6자리 16진수 + 3자 이하 이름으로 만든다
    private final String run = UUID.randomUUID().toString().substring(0, 6);

    private record LoginTokens(String accessToken, String refreshToken) {
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "me-upd-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "me-upd-" + run + "-" + name + "@example.com";
    }

    private String nickname(String name) {
        return "u" + run + name;
    }

    private Map<String, Object> insertUser(String name) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id, public_id
                """, email(name), passwordEncoder.encode(PASSWORD), nickname(name));
    }

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private ResultActions postLogin(String name, String password) throws Exception {
        String csrf = issueCsrfToken();
        return mockMvc.perform(post(LOGIN_PATH)
                .cookie(new Cookie(XSRF_COOKIE, csrf))
                .header(XSRF_HEADER, csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(name) + "\",\"password\":\"" + password + "\"}"));
    }

    private LoginTokens login(String name) throws Exception {
        MockHttpServletResponse response = postLogin(name, PASSWORD).andExpect(status().isOk()).andReturn().getResponse();
        return new LoginTokens(cookieValue(response, "access_token"), cookieValue(response, "refresh_token"));
    }

    private static String cookieValue(MockHttpServletResponse response, String name) {
        String setCookie = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    // CSRF 토큰을 붙인 PATCH. accessToken이 null이면 인증 쿠키를 보내지 않는다
    private ResultActions patchWithCsrf(String path, String accessToken, String json) throws Exception {
        String csrf = issueCsrfToken();
        var request = patch(path)
                .cookie(new Cookie(XSRF_COOKIE, csrf))
                .header(XSRF_HEADER, csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (accessToken != null) {
            request.cookie(new Cookie("access_token", accessToken));
        }
        return mockMvc.perform(request);
    }

    private ResultActions patchNickname(String accessToken, String nickname) throws Exception {
        return patchWithCsrf(ME_PATH, accessToken, "{\"nickname\":\"" + nickname + "\"}");
    }

    private ResultActions patchPassword(String accessToken, String currentPassword, String newPassword) throws Exception {
        return patchWithCsrf(PASSWORD_PATH, accessToken,
                "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private static void assertError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private String storedNickname(String name) {
        return jdbcTemplate.queryForObject("SELECT nickname FROM users WHERE email = ?", String.class, email(name));
    }

    // 닉네임 수정 성공

    // M00-02: 응답은 GET과 같은 7개 필드이고 roles는 요청 Access Token의 권한이다
    @Test
    @DisplayName("닉네임을 바꾸면 200과 GET과 같은 7개 필드를 받고, roles는 요청 토큰의 권한이며 GET에도 반영된다")
    void changesNickname() throws Exception {
        // given: DB에서는 판매자가 아니지만 토큰 권한에 SELLER가 있다
        Map<String, Object> user = insertUser("ok");
        String token = jwtProvider.issue((UUID) user.get("public_id"), Set.of(AuthRole.USER, AuthRole.SELLER)).value();

        // when
        String body = patchNickname(token, "  " + nickname("new") + "  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.get("public_id").toString()))
                .andExpect(jsonPath("$.data.nickname").value(nickname("new")))
                .andExpect(jsonPath("$.data.provider").value("LOCAL"))
                .andExpect(jsonPath("$.data.roles", contains("USER", "SELLER")))
                .andReturn().getResponse().getContentAsString();

        // then
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).containsOnlyKeys(
                "userId", "email", "nickname", "provider", "roles", "profileImageUrl", "createdAt");
        mockMvc.perform(get(ME_PATH).cookie(new Cookie("access_token", token)))
                .andExpect(jsonPath("$.data.nickname").value(nickname("new")));
    }

    @Test
    @DisplayName("본인 닉네임의 대소문자만 바꾸는 것은 허용된다")
    void allowsCaseOnlyChange() throws Exception {
        // given
        insertUser("cs");
        LoginTokens tokens = login("cs");

        // when & then
        patchNickname(tokens.accessToken(), nickname("cs").toUpperCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(nickname("cs").toUpperCase()));
    }

    // 닉네임 수정 실패

    @ParameterizedTest
    @ValueSource(strings = {"가", "드롭 헌터", "nick-name"})
    @DisplayName("닉네임 규칙을 어기면 400 INVALID_NICKNAME이고 기존 값이 유지된다")
    void rejectsInvalidNickname(String invalid) throws Exception {
        // given
        insertUser("invalid");
        LoginTokens tokens = login("invalid");

        // when & then
        assertError(patchNickname(tokens.accessToken(), invalid), 400, "INVALID_NICKNAME");
        assertThat(storedNickname("invalid")).isEqualTo(nickname("invalid"));
    }

    // M00-05: 앞뒤 공백을 제거한 값이 현재 닉네임과 대소문자까지 같으면 거부한다
    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("현재 닉네임과 같은 값(앞뒤 공백만 다른 값 포함)은 400 NICKNAME_UNCHANGED다")
    void rejectsUnchangedNickname(String padding) throws Exception {
        // given
        insertUser("sm");
        LoginTokens tokens = login("sm");

        // when & then
        assertError(patchNickname(tokens.accessToken(), padding + nickname("sm") + padding), 400, "NICKNAME_UNCHANGED");
    }

    @Test
    @DisplayName("다른 회원의 닉네임과 대소문자만 달라도 409 DUPLICATE_NICKNAME이고 기존 값이 유지된다")
    void rejectsDuplicateNickname() throws Exception {
        // given
        insertUser("ot");
        insertUser("dup");
        LoginTokens tokens = login("dup");

        // when & then
        assertError(patchNickname(tokens.accessToken(), nickname("ot").toUpperCase()), 409, "DUPLICATE_NICKNAME");
        assertThat(storedNickname("dup")).isEqualTo(nickname("dup"));
    }

    // 비밀번호 변경 성공

    @Test
    @DisplayName("비밀번호를 바꾸면 204이고, 새 비밀번호로 로그인되며 이전 비밀번호는 401 INVALID_CREDENTIALS다")
    void changesPassword() throws Exception {
        // given
        insertUser("pw");
        LoginTokens tokens = login("pw");

        // when
        patchPassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        // then
        postLogin("pw", NEW_PASSWORD).andExpect(status().isOk());
        assertError(postLogin("pw", PASSWORD), 401, "INVALID_CREDENTIALS");
    }

    // M00-03: 비밀번호 변경은 기존 로그인 세션을 종료하지 않는다
    @Test
    @DisplayName("비밀번호를 바꿔도 변경 전에 받은 Refresh Token으로 재발급할 수 있다")
    void keepsExistingSessions() throws Exception {
        // given
        insertUser("session");
        LoginTokens tokens = login("session");
        patchPassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        // when & then
        String csrf = issueCsrfToken();
        mockMvc.perform(post(REFRESH_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf), new Cookie("refresh_token", tokens.refreshToken()))
                        .header(XSRF_HEADER, csrf))
                .andExpect(status().isNoContent());
    }

    // 비밀번호 변경 실패. M00-01: 모두 401이 아닌 400이라 클라이언트가 재발급·로그아웃 흐름을 타지 않는다

    @Test
    @DisplayName("현재 비밀번호가 틀리면 400 PASSWORD_MISMATCH이고 비밀번호가 바뀌지 않는다")
    void rejectsWrongCurrentPassword() throws Exception {
        // given
        insertUser("mismatch");
        LoginTokens tokens = login("mismatch");

        // when & then
        assertError(patchPassword(tokens.accessToken(), "Wrong123!", NEW_PASSWORD), 400, "PASSWORD_MISMATCH");
        postLogin("mismatch", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("새 비밀번호가 현재와 같으면 400 PASSWORD_UNCHANGED다")
    void rejectsUnchangedPassword() throws Exception {
        // given
        insertUser("unchanged");
        LoginTokens tokens = login("unchanged");

        // when & then
        assertError(patchPassword(tokens.accessToken(), PASSWORD, PASSWORD), 400, "PASSWORD_UNCHANGED");
    }

    @Test
    @DisplayName("새 비밀번호가 규칙을 어기면 400 INVALID_PASSWORD다")
    void rejectsInvalidNewPassword() throws Exception {
        // given
        insertUser("rule");
        LoginTokens tokens = login("rule");

        // when & then
        assertError(patchPassword(tokens.accessToken(), PASSWORD, "short"), 400, "INVALID_PASSWORD");
    }

    @Test
    @DisplayName("소셜 회원은 400 PASSWORD_NOT_AVAILABLE이다")
    void rejectsSocialUser() throws Exception {
        // given: 소셜 회원은 비밀번호가 없어 로그인 API 대신 토큰을 직접 발급한다
        Map<String, Object> user = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, nickname, status, provider, provider_user_id) VALUES (?, ?, 'ACTIVE', 'KAKAO', ?)
                RETURNING public_id
                """, email("kakao"), nickname("kakao"), "sub-" + run);
        String token = jwtProvider.issue((UUID) user.get("public_id"), Set.of(AuthRole.USER)).value();

        // when & then
        assertError(patchPassword(token, PASSWORD, NEW_PASSWORD), 400, "PASSWORD_NOT_AVAILABLE");
    }

    // 공통: 인증·권한·CSRF(M00-06)

    @Test
    @DisplayName("access_token 쿠키가 없으면 두 PATCH 모두 401 AUTHENTICATION_REQUIRED다")
    void rejectsAnonymous() throws Exception {
        assertError(patchNickname(null, nickname("anon")), 401, "AUTHENTICATION_REQUIRED");
        assertError(patchPassword(null, PASSWORD, NEW_PASSWORD), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("로그인 후 정지·탈퇴된 회원은 두 PATCH 모두 401 AUTHENTICATION_REQUIRED다")
    void rejectsInactiveUser(String status) throws Exception {
        // given
        insertUser("inactive");
        LoginTokens tokens = login("inactive");
        jdbcTemplate.update("UPDATE users SET status = ? WHERE email = ?", status, email("inactive"));

        // when & then
        assertError(patchNickname(tokens.accessToken(), nickname("new")), 401, "AUTHENTICATION_REQUIRED");
        assertError(patchPassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("USER 권한이 없는 토큰이면 두 PATCH 모두 403 ACCESS_DENIED다")
    void rejectsTokenWithoutUserRole() throws Exception {
        // given
        Map<String, Object> user = insertUser("admin");
        String token = jwtProvider.issue((UUID) user.get("public_id"), Set.of(AuthRole.ADMIN)).value();

        // when & then
        assertError(patchNickname(token, nickname("new")), 403, "ACCESS_DENIED");
        assertError(patchPassword(token, PASSWORD, NEW_PASSWORD), 403, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 두 PATCH 모두 403 ACCESS_DENIED이고 값이 바뀌지 않는다")
    void rejectsMissingCsrf() throws Exception {
        // given
        insertUser("csrf");
        LoginTokens tokens = login("csrf");
        Cookie access = new Cookie("access_token", tokens.accessToken());

        // when & then
        assertError(mockMvc.perform(patch(ME_PATH).cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname("new") + "\"}")), 403, "ACCESS_DENIED");
        assertError(mockMvc.perform(patch(PASSWORD_PATH).cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")),
                403, "ACCESS_DENIED");
        assertThat(storedNickname("csrf")).isEqualTo(nickname("csrf"));
        postLogin("csrf", PASSWORD).andExpect(status().isOk());
    }
}
