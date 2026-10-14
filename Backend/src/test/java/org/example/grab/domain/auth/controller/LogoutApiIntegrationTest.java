package org.example.grab.domain.auth.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.support.RefreshTokenHasher;
import org.example.grab.global.security.jwt.AccessTokenProperties;
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
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-36 M02-03: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL·Redis로 로그아웃(MEMBER_AUTH 1.8)을 확인한다.
    로그인 API로 받은 토큰을 쓰고, CSRF는 프론트엔드처럼 GET /api/v1/auth/csrf로 받은 값을 헤더에 넣는다.
    인증 쿠키는 ResponseCookie 문자열로 Set-Cookie 헤더에 쓰이므로 헤더 문자열로 속성을 단언한다(TokenRefreshApiIntegrationTest와 같음).
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일로 회원을 만들고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class LogoutApiIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String PROTECTED_PATH = "/api/v1/users/me/wishes";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private AccessTokenProperties accessTokenProperties;

    private final String run = UUID.randomUUID().toString().substring(0, 6);

    // 로그인으로 받은 두 인증 쿠키 값
    private record LoginTokens(String accessToken, String refreshToken) {
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "logout-api-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "logout-api-" + run + "-" + name + "@example.com";
    }

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    // 회원을 만들지 않고 같은 회원으로 다시 로그인해 별도 세션을 만들 수 있게 나눈다
    private void insertUser(String name) {
        jdbcTemplate.update("INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, 'ACTIVE')",
                email(name), passwordEncoder.encode(PASSWORD), "logout-" + run + "-" + name);
    }

    private LoginTokens login(String name) throws Exception {
        String csrf = issueCsrfToken();
        MockHttpServletResponse response = mockMvc.perform(post(LOGIN_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf))
                        .header(XSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(name) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return new LoginTokens(cookieValue(setCookie(response, "access_token").orElseThrow()),
                cookieValue(setCookie(response, "refresh_token").orElseThrow()));
    }

    private LoginTokens signUpAndLogin(String name) throws Exception {
        insertUser(name);
        return login(name);
    }

    // 지정한 쿠키에 XSRF-TOKEN 쿠키와 같은 값의 헤더를 붙여 POST한다
    private ResultActions postWithCsrf(String path, Cookie... cookies) throws Exception {
        String csrf = issueCsrfToken();
        Cookie[] all = new Cookie[cookies.length + 1];
        all[0] = new Cookie(XSRF_COOKIE, csrf);
        System.arraycopy(cookies, 0, all, 1, cookies.length);
        return mockMvc.perform(post(path).cookie(all).header(XSRF_HEADER, csrf));
    }

    private ResultActions logout(Cookie... cookies) throws Exception {
        return postWithCsrf(LOGOUT_PATH, cookies);
    }

    private ResultActions logoutWith(String refreshToken) throws Exception {
        return logout(new Cookie("refresh_token", refreshToken));
    }

    private ResultActions refreshWith(String refreshToken) throws Exception {
        return postWithCsrf(REFRESH_PATH, new Cookie("refresh_token", refreshToken));
    }

    private String rotate(String refreshToken) throws Exception {
        MockHttpServletResponse response = refreshWith(refreshToken)
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        return cookieValue(setCookie(response, "refresh_token").orElseThrow());
    }

    private static Optional<String> setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst();
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    // 204·빈 본문과 두 만료 쿠키. 브라우저는 이름·Path가 같아야 같은 쿠키로 보므로 발급과 같은 Path·속성이어야 지워진다
    private static void assertLoggedOut(ResultActions result) throws Exception {
        MockHttpServletResponse response = result
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).isEmpty();
        String access = setCookie(response, "access_token").orElseThrow();
        String refresh = setCookie(response, "refresh_token").orElseThrow();
        assertThat(access).startsWith("access_token=;")
                .contains("Path=/api;", "Max-Age=0;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(refresh).startsWith("refresh_token=;")
                .contains("Path=/api/v1/auth;", "Max-Age=0;", "HttpOnly", "Secure", "SameSite=Lax");
        // XSRF-TOKEN은 교체·삭제하지 않는다(GR-34 M00-06과 같은 기준)
        assertThat(setCookie(response, XSRF_COOKIE)).isEmpty();
    }

    private static void assertInvalidToken(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    private boolean hasSessionKeys(String refreshToken, UUID sessionId) {
        return redisTemplate.hasKey(RefreshTokenHasher.toKey(refreshToken))
                || redisTemplate.hasKey(RefreshTokenHasher.toSessionKey(sessionId));
    }

    private String expiredAccessToken() {
        Instant expiredAt = Instant.now().minus(Duration.ofMinutes(5));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .issuer(accessTokenProperties.issuer())
                .audience().add(accessTokenProperties.audience()).and()
                .issuedAt(Date.from(expiredAt.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiredAt))
                .signWith(Keys.hmacShaKeyFor(accessTokenProperties.secretBytes()), Jwts.SIG.HS256)
                .compact();
    }

    // 성공

    // 완료 조건 1·2: 로그아웃 후 그 Refresh Token으로 재발급할 수 없고, 두 쿠키의 Max-Age가 0이다
    @Test
    @DisplayName("로그아웃하면 204와 두 만료 쿠키를 받고, Redis의 토큰·세션 키가 지워져 그 토큰으로 재발급할 수 없다")
    void logsOutAndRevokesSession() throws Exception {
        // given
        LoginTokens tokens = signUpAndLogin("ok");
        UUID sessionId = refreshTokenRepository.find(tokens.refreshToken()).orElseThrow().sessionId();

        // when
        assertLoggedOut(logout(new Cookie("access_token", tokens.accessToken()),
                new Cookie("refresh_token", tokens.refreshToken())));

        // then
        assertThat(hasSessionKeys(tokens.refreshToken(), sessionId)).isFalse();
        assertInvalidToken(refreshWith(tokens.refreshToken()));
    }

    // 완료 조건 3·M00-01: 반복·쿠키 없음·모르는 값도 같은 결과다
    @Test
    @DisplayName("같은 토큰으로 반복 로그아웃, 쿠키가 지워진 뒤 요청, 발급한 적 없는 값도 모두 204와 두 만료 쿠키다")
    void logoutIsIdempotent() throws Exception {
        // given
        LoginTokens tokens = signUpAndLogin("repeat");
        assertLoggedOut(logoutWith(tokens.refreshToken()));

        // when & then
        assertLoggedOut(logoutWith(tokens.refreshToken()));
        assertLoggedOut(logout());
        assertLoggedOut(logoutWith("unknown-token"));
    }

    @Test
    @DisplayName("TTL로 이미 만료된 Refresh Token으로 로그아웃해도 204와 두 만료 쿠키다")
    void logsOutWithExpiredRefreshToken() throws Exception {
        // given: 키 TTL을 줄여 Redis가 실제로 지우게 한다
        LoginTokens tokens = signUpAndLogin("expired");
        UUID sessionId = refreshTokenRepository.find(tokens.refreshToken()).orElseThrow().sessionId();
        redisTemplate.expire(RefreshTokenHasher.toKey(tokens.refreshToken()), Duration.ofMillis(100));
        redisTemplate.expire(RefreshTokenHasher.toSessionKey(sessionId), Duration.ofMillis(100));
        Thread.sleep(300);
        assertThat(hasSessionKeys(tokens.refreshToken(), sessionId)).isFalse();

        // when & then
        assertLoggedOut(logoutWith(tokens.refreshToken()));
    }

    // M00-02: 탈취자가 A로 먼저 재발급해 B를 받아도, A를 가진 사용자의 로그아웃으로 세션이 끊긴다
    @Test
    @DisplayName("교체된 옛 토큰으로 로그아웃해도 그 세션의 새 토큰이 폐기되어 재발급할 수 없다")
    void logoutWithRotatedTokenRevokesNewToken() throws Exception {
        // given
        LoginTokens tokens = signUpAndLogin("stolen");
        UUID sessionId = refreshTokenRepository.find(tokens.refreshToken()).orElseThrow().sessionId();
        String newToken = rotate(tokens.refreshToken());

        // when
        assertLoggedOut(logoutWith(tokens.refreshToken()));

        // then
        assertThat(hasSessionKeys(newToken, sessionId)).isFalse();
        assertInvalidToken(refreshWith(newToken));
    }

    // M00-01: Access Token이 만료된 뒤에도 재발급 없이 로그아웃할 수 있다
    @Test
    @DisplayName("access_token 쿠키가 없거나 만료·변조돼도 유효한 CSRF와 refresh_token으로 로그아웃할 수 있다")
    void logsOutWithoutValidAccessToken() throws Exception {
        // given
        insertUser("stale");
        String missing = login("stale").refreshToken();
        String expired = login("stale").refreshToken();
        String tampered = login("stale").refreshToken();

        // when & then
        assertLoggedOut(logoutWith(missing));
        assertLoggedOut(logout(new Cookie("access_token", expiredAccessToken()), new Cookie("refresh_token", expired)));
        assertLoggedOut(logout(new Cookie("access_token", "not-a-jwt"), new Cookie("refresh_token", tampered)));
        assertThat(refreshTokenRepository.find(missing)).isEmpty();
        assertThat(refreshTokenRepository.find(expired)).isEmpty();
        assertThat(refreshTokenRepository.find(tampered)).isEmpty();
    }

    // 완료 조건 4: 다른 사용자의 Refresh Token에는 영향이 없다
    @Test
    @DisplayName("로그아웃해도 다른 사용자의 세션과 같은 사용자의 다른 로그인 세션은 재발급할 수 있다")
    void keepsOtherSessions() throws Exception {
        // given: 같은 사용자로 두 번 로그인해 세션 두 개를 만든다
        insertUser("me");
        LoginTokens loggedOut = login("me");
        LoginTokens otherDevice = login("me");
        LoginTokens otherUser = signUpAndLogin("other");

        // when
        assertLoggedOut(logoutWith(loggedOut.refreshToken()));

        // then
        refreshWith(otherDevice.refreshToken()).andExpect(status().isNoContent());
        refreshWith(otherUser.refreshToken()).andExpect(status().isNoContent());
    }

    // M00-04: Access Token은 서버에 저장하지 않으므로 쿠키만 지워지고 JWT 자체는 만료 시각까지 유효하다
    @Test
    @DisplayName("로그아웃 후 쿠키 없이 보호 API를 부르면 401이고, 따로 보관한 Access Token은 만료 전까지 인증된다")
    void accessTokenRemainsValidUntilExpiry() throws Exception {
        // given
        LoginTokens tokens = signUpAndLogin("access");
        assertLoggedOut(logout(new Cookie("access_token", tokens.accessToken()),
                new Cookie("refresh_token", tokens.refreshToken())));

        // when & then: 브라우저처럼 쿠키가 지워진 상태
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        // when & then: 로그아웃 전에 복사한 값(MEMBER_AUTH 1.8의 잔여 유효기간 정책)
        mockMvc.perform(get(PROTECTED_PATH).cookie(new Cookie("access_token", tokens.accessToken())))
                .andExpect(status().isOk());
    }

    // 실패

    @Test
    @DisplayName("CSRF 토큰이 없거나 다르면 403 ACCESS_DENIED이고 세션과 인증 쿠키가 그대로다")
    void rejectsWithoutValidCsrfToken() throws Exception {
        // given
        LoginTokens tokens = signUpAndLogin("csrf");
        UUID sessionId = refreshTokenRepository.find(tokens.refreshToken()).orElseThrow().sessionId();
        String csrf = issueCsrfToken();

        // when
        MockHttpServletResponse missing = mockMvc.perform(post(LOGOUT_PATH)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andReturn().getResponse();
        MockHttpServletResponse mismatched = mockMvc.perform(post(LOGOUT_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf), new Cookie("refresh_token", tokens.refreshToken()))
                        .header(XSRF_HEADER, csrf + "-other"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andReturn().getResponse();

        // then
        for (MockHttpServletResponse response : List.of(missing, mismatched)) {
            assertThat(setCookie(response, "access_token")).isEmpty();
            assertThat(setCookie(response, "refresh_token")).isEmpty();
        }
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toKey(tokens.refreshToken()))).isTrue();
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toSessionKey(sessionId))).isTrue();
        refreshWith(tokens.refreshToken()).andExpect(status().isNoContent());
    }
}
