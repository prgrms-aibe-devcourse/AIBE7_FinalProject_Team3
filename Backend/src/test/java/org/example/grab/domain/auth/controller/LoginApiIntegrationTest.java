package org.example.grab.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.RefreshTokenHasher;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-34 M02-02~04: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL·Redis로 LOCAL 로그인(MEMBER_AUTH 1.3)을 확인한다.
    CSRF는 csrf() 후처리기 대신 프론트엔드처럼 GET /api/v1/auth/csrf로 받은 XSRF-TOKEN 값을 헤더에 넣는다(GR-44 완료 조건 6 이관).
    인증 쿠키는 ResponseCookie 문자열로 Set-Cookie 헤더에 쓰이므로 헤더 문자열로 속성(SameSite 포함)을 단언한다.
    쿠키 이름은 상수 대신 명세 문자열로 단언한다(GR-44 M01-04).
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일로 회원을 만들고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class LoginApiIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "Password123!";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final Pattern MAX_AGE = Pattern.compile("Max-Age=(\\d+)");

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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // 테스트가 쓴 이메일의 공통 접두어. 다른 테스트의 데이터와 섞이지 않게 한다
    private final String run = UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sellers WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "login-api-" + run + "-%");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "login-api-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "login-api-" + run + "-" + name + "@example.com";
    }

    // 가입(GR-29)과 같은 인코더로 해시한 LOCAL 회원을 만든다
    private Map<String, Object> insertUser(String name, String status) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, ?)
                RETURNING id, public_id
                """, email(name), passwordEncoder.encode(PASSWORD), "login-" + run + "-" + name, status);
    }

    private Map<String, Object> insertUser(String name) {
        return insertUser(name, "ACTIVE");
    }

    // 프론트엔드처럼 CSRF 토큰 쿠키를 받아 그 값을 꺼낸다
    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private ResultActions login(String body, Cookie... extraCookies) throws Exception {
        String token = issueCsrfToken();
        Cookie[] cookies = new Cookie[extraCookies.length + 1];
        cookies[0] = new Cookie(XSRF_COOKIE, token);
        System.arraycopy(extraCookies, 0, cookies, 1, extraCookies.length);
        return mockMvc.perform(post(LOGIN_PATH)
                .cookie(cookies)
                .header(XSRF_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static Optional<String> setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst();
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static long maxAge(String setCookie) {
        Matcher matcher = MAX_AGE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private Long countRefreshSessions() {
        return redisTemplate.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
    }

    private void assertNoLoginSideEffects(MockHttpServletResponse response) {
        assertThat(setCookie(response, "access_token")).isEmpty();
        assertThat(setCookie(response, "refresh_token")).isEmpty();
        assertThat(countRefreshSessions()).isZero();
    }

    // M02-02 성공

    @Test
    @DisplayName("올바른 계정은 200과 data.user를 받고, 본문에는 토큰이 없다")
    void returnsUserWithoutTokensInBody() throws Exception {
        // given
        Map<String, Object> user = insertUser("ok");

        // when
        MockHttpServletResponse response = login(body(email("ok"), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.userId").value(user.get("public_id").toString()))
                .andExpect(jsonPath("$.data.user.email").value(email("ok")))
                .andExpect(jsonPath("$.data.user.nickname").value("login-" + run + "-ok"))
                .andExpect(jsonPath("$.data.user.roles.length()").value(1))
                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.user.id").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();

        // then: 쿠키로 받은 두 토큰 값이 본문 어디에도 없다
        String body = response.getContentAsString();
        assertThat(body)
                .doesNotContain(cookieValue(setCookie(response, "access_token").orElseThrow()))
                .doesNotContain(cookieValue(setCookie(response, "refresh_token").orElseThrow()))
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("인증 쿠키 두 개는 명세 속성(HttpOnly·Secure·SameSite=Lax·Path)을 갖고, Access Max-Age는 JWT exp를 넘지 않는다")
    void issuesAuthCookiesWithSpecAttributes() throws Exception {
        // given
        insertUser("cookie");
        Instant before = Instant.now();

        // when
        MockHttpServletResponse response = login(body(email("cookie"), PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Instant after = Instant.now();

        // then: access_token
        String access = setCookie(response, "access_token").orElseThrow();
        assertThat(access).contains("Path=/api;", "HttpOnly", "Secure", "SameSite=Lax");
        Instant exp = jwtProvider.parse(cookieValue(access)).getExpiration().toInstant();
        assertThat(maxAge(access))
                .isLessThanOrEqualTo(Duration.between(before, exp).toSeconds())
                .isGreaterThanOrEqualTo(Duration.between(after, exp).toSeconds() - 1)
                .isBetween(Duration.ofMinutes(15).toSeconds() - 2, Duration.ofMinutes(15).toSeconds());

        // then: refresh_token
        String refresh = setCookie(response, "refresh_token").orElseThrow();
        assertThat(refresh).contains("Path=/api/v1/auth;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(maxAge(refresh)).isEqualTo(Duration.ofDays(14).toSeconds());
    }

    @Test
    @DisplayName("Refresh 세션은 원문 대신 해시 키로 Redis에 TTL 14일로 저장되고, 절대 만료는 로그인 시각 + 30일이다")
    void storesRefreshSession() throws Exception {
        // given
        Map<String, Object> user = insertUser("redis");
        Instant before = Instant.now();

        // when
        MockHttpServletResponse response = login(body(email("redis"), PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Instant after = Instant.now();

        // then
        String refreshToken = cookieValue(setCookie(response, "refresh_token").orElseThrow());
        String key = RefreshTokenHasher.toKey(refreshToken);
        assertThat(redisTemplate.hasKey(key)).isTrue();
        assertThat(redisTemplate.keys("*" + refreshToken + "*")).isEmpty();
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                .isBetween(Duration.ofDays(14).toSeconds() - 5, Duration.ofDays(14).toSeconds());

        RefreshTokenSession session = refreshTokenRepository.find(refreshToken).orElseThrow();
        assertThat(session.userId()).isEqualTo(user.get("id"));
        assertThat(session.sessionExpiresAt())
                .isBetween(before.plus(Duration.ofDays(30)), after.plus(Duration.ofDays(30)));

        // GR-35 M00-02: 세션 키가 로그인 토큰의 해시를 가리킨다
        assertThat(redisTemplate.opsForValue().get(RefreshTokenHasher.toSessionKey(session.sessionId())))
                .isEqualTo(RefreshTokenHasher.hash(refreshToken));
    }

    @Test
    @DisplayName("발급받은 access_token으로 보호 API를 호출할 수 있다")
    void accessTokenAuthenticatesProtectedApi() throws Exception {
        // given
        insertUser("use");
        MockHttpServletResponse response = login(body(email("use"), PASSWORD)).andReturn().getResponse();
        String accessToken = cookieValue(setCookie(response, "access_token").orElseThrow());

        // when & then
        mockMvc.perform(get("/api/v1/users/me/wishes").cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("승인된 판매자는 응답 roles에 SELLER가 포함된다")
    void includesSellerRole() throws Exception {
        // given
        Map<String, Object> seller = insertUser("seller");
        Map<String, Object> reviewer = insertUser("reviewer");
        jdbcTemplate.update("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                """, seller.get("id"), email("seller-contact"), reviewer.get("id"));

        // when & then
        login(body(email("seller"), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles.length()").value(2))
                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.user.roles[1]").value("SELLER"));
    }

    @Test
    @DisplayName("대소문자·앞뒤 공백이 다른 이메일도 정규화해 로그인된다")
    void normalizesEmail() throws Exception {
        // given
        insertUser("case");

        // when & then
        login(body("  " + email("case").toUpperCase() + " ", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email("case")));
    }

    @Test
    @DisplayName("만료·변조된 access_token 쿠키를 가진 채로도 로그인할 수 있다")
    void logsInWithInvalidAccessToken() throws Exception {
        // given
        insertUser("stale");

        // when & then
        login(body(email("stale"), PASSWORD), new Cookie("access_token", "not-a-jwt"))
                .andExpect(status().isOk());
    }

    // M02-02 실패

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호는 상태·본문이 같은 401 INVALID_CREDENTIALS이고 쿠키·세션이 생기지 않는다")
    void returnsSameResponseForUnknownEmailAndWrongPassword() throws Exception {
        // given
        insertUser("wrong");

        // when
        MockHttpServletResponse unknown = login(body(email("unknown"), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse();
        MockHttpServletResponse wrong = login(body(email("wrong"), "Wrong123!"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        // then
        assertThat(wrong.getContentAsString()).isEqualTo(unknown.getContentAsString());
        assertNoLoginSideEffects(unknown);
        assertNoLoginSideEffects(wrong);
    }

    @Test
    @DisplayName("비밀번호가 없는 소셜 회원은 LOCAL 로그인에서 401 INVALID_CREDENTIALS")
    void rejectsSocialUser() throws Exception {
        // given
        jdbcTemplate.update("INSERT INTO users (email, nickname, provider) VALUES (?, ?, 'KAKAO')",
                email("kakao"), "login-" + run + "-kakao");

        // when & then
        MockHttpServletResponse response = login(body(email("kakao"), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse();
        assertNoLoginSideEffects(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("비밀번호가 맞은 비활성 계정은 403 ACCOUNT_DISABLED, 틀리면 401 INVALID_CREDENTIALS이고 쿠키·세션이 생기지 않는다")
    void rejectsDisabledAccount(String status) throws Exception {
        // given
        insertUser("disabled", status);

        // when & then
        MockHttpServletResponse correct = login(body(email("disabled"), PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"))
                .andReturn().getResponse();
        MockHttpServletResponse wrong = login(body(email("disabled"), "Wrong123!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse();
        assertNoLoginSideEffects(correct);
        assertNoLoginSideEffects(wrong);
    }

    @Test
    @DisplayName("이메일·비밀번호가 없으면 400 VALIDATION_FAILED, 본문이 JSON이 아니면 400 INVALID_REQUEST")
    void rejectsMissingFields() throws Exception {
        login("{\"email\":\"  \",\"password\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(2));
        login("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        login("not-json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(countRefreshSessions()).isZero();
    }

    // M02-03 CSRF·CORS

    @Test
    @DisplayName("CSRF 토큰이 없거나 헤더와 쿠키가 다르면 올바른 계정이어도 403 ACCESS_DENIED이고 로그인되지 않는다")
    void rejectsLoginWithoutCsrfToken() throws Exception {
        // given
        insertUser("csrf");
        String token = issueCsrfToken();

        // 토큰 없음
        MockHttpServletResponse missing = mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email("csrf"), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andReturn().getResponse();
        // 헤더와 쿠키가 다름
        MockHttpServletResponse mismatched = mockMvc.perform(post(LOGIN_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, issueCsrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email("csrf"), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andReturn().getResponse();

        // then: Controller까지 갔다면 올바른 계정이라 쿠키와 세션이 생겼을 것이다
        assertNoLoginSideEffects(missing);
        assertNoLoginSideEffects(mismatched);
    }

    @Test
    @DisplayName("로그인은 XSRF-TOKEN을 교체하지 않아, 로그인 전에 받은 토큰으로 로그인 후 상태 변경 요청이 CSRF를 통과한다")
    void keepsCsrfTokenAfterLogin() throws Exception {
        // given
        insertUser("keep");
        String token = issueCsrfToken();

        // when: 로그인 응답에 새 XSRF-TOKEN이 없다(M00-06)
        MockHttpServletResponse response = mockMvc.perform(post(LOGIN_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email("keep"), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        // CSRF 저장소는 Servlet Cookie로 쓰므로 헤더 문자열과 쿠키 객체를 함께 본다
        assertThat(setCookie(response, XSRF_COOKIE)).isEmpty();
        assertThat(response.getCookie(XSRF_COOKIE)).isNull();
        String accessToken = cookieValue(setCookie(response, "access_token").orElseThrow());

        // then: 같은 토큰으로 보호 경로 상태 변경 요청이 CSRF를 통과한다(DROP이 없어 비즈니스 오류여도 403은 아니다)
        mockMvc.perform(put("/api/v1/drops/0/wish")
                        .cookie(new Cookie("access_token", accessToken), new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("허용 Origin의 preflight와 Credential 로그인 요청은 통과하고, 다른 Origin은 CSRF 토큰이 있어도 403")
    void appliesCorsToLogin() throws Exception {
        // given
        insertUser("cors");
        String token = issueCsrfToken();

        // preflight
        mockMvc.perform(options(LOGIN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        // 허용 Origin의 실제 요청
        mockMvc.perform(post(LOGIN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email("cors"), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });

        // 다른 Origin
        MockHttpServletResponse rejected = mockMvc.perform(post(LOGIN_PATH)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .header(XSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email("cors"), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andReturn().getResponse();
        assertNoLoginSideEffects(rejected);
    }

    // M02-04 쿠키 접근성

    @Test
    @DisplayName("로그인으로 받은 access_token·refresh_token은 HttpOnly이고, JavaScript가 읽을 수 있는 쿠키는 XSRF-TOKEN뿐이다")
    void exposesOnlyCsrfCookieToJavaScript() throws Exception {
        // given
        insertUser("js");

        // when
        MockHttpServletResponse response = login(body(email("js"), PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // then
        List<String> authCookies = List.of(
                setCookie(response, "access_token").orElseThrow(),
                setCookie(response, "refresh_token").orElseThrow());
        assertThat(authCookies).allSatisfy(value -> assertThat(value).contains("HttpOnly"));
        mockMvc.perform(get(CSRF_PATH))
                .andExpect(cookie().httpOnly(XSRF_COOKIE, false));
    }
}
