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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-35 M02-03: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL·Redis로 토큰 재발급(MEMBER_AUTH 1.7)을 확인한다.
    로그인 API로 받은 refresh_token을 쓰고, CSRF는 프론트엔드처럼 GET /api/v1/auth/csrf로 받은 값을 헤더에 넣는다.
    인증 쿠키는 ResponseCookie 문자열로 Set-Cookie 헤더에 쓰이므로 헤더 문자열로 속성을 단언한다(LoginApiIntegrationTest와 같음).
    재사용 유예는 1초로 줄여 유예 안·밖을 실제로 기다려 확인한다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일로 회원을 만들고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class TokenRefreshApiIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String PROTECTED_PATH = "/api/v1/users/me/wishes";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "Password123!";
    private static final Duration REUSE_GRACE = Duration.ofSeconds(1);
    private static final Pattern MAX_AGE = Pattern.compile("Max-Age=(\\d+)");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다
        registry.add("spring.data.redis.password", () -> "");
        registry.add("grab.auth.refresh-token.reuse-grace", () -> REUSE_GRACE.toString());
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

    private final String run = UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sellers WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "refresh-api-" + run + "-%");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "refresh-api-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "refresh-api-" + run + "-" + name + "@example.com";
    }

    private Map<String, Object> insertUser(String name) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id, public_id
                """, email(name), passwordEncoder.encode(PASSWORD), "refresh-" + run + "-" + name);
    }

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    // 회원을 만들고 로그인해 받은 refresh_token 원문을 돌려준다
    private String loginAndGetRefreshToken(String name) throws Exception {
        insertUser(name);
        String csrf = issueCsrfToken();
        MockHttpServletResponse response = mockMvc.perform(post(LOGIN_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf))
                        .header(XSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(name) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return cookieValue(setCookie(response, "refresh_token").orElseThrow());
    }

    private ResultActions refresh(Cookie... extraCookies) throws Exception {
        String csrf = issueCsrfToken();
        Cookie[] cookies = new Cookie[extraCookies.length + 1];
        cookies[0] = new Cookie(XSRF_COOKIE, csrf);
        System.arraycopy(extraCookies, 0, cookies, 1, extraCookies.length);
        return mockMvc.perform(post(REFRESH_PATH).cookie(cookies).header(XSRF_HEADER, csrf));
    }

    private ResultActions refreshWith(String refreshToken) throws Exception {
        return refresh(new Cookie("refresh_token", refreshToken));
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

    private void assertInvalidTokenWithoutCookies(ResultActions result) throws Exception {
        MockHttpServletResponse response = result
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                .andReturn().getResponse();
        assertThat(setCookie(response, "access_token")).isEmpty();
        assertThat(setCookie(response, "refresh_token")).isEmpty();
    }

    // 성공

    @Test
    @DisplayName("유효한 refresh_token이면 204와 새 인증 쿠키 두 개를 받고, 본문에는 토큰이 없다")
    void refreshesTokens() throws Exception {
        // given
        String oldToken = loginAndGetRefreshToken("ok");

        // when
        MockHttpServletResponse response = refreshWith(oldToken)
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        // then: 쿠키 속성은 로그인과 같다. 브라우저는 이름·Path가 같아야 기존 쿠키를 덮어쓴다
        assertThat(response.getContentAsString()).isEmpty();
        String access = setCookie(response, "access_token").orElseThrow();
        String refresh = setCookie(response, "refresh_token").orElseThrow();
        assertThat(access).contains("Path=/api;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(refresh).contains("Path=/api/v1/auth;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(maxAge(access)).isBetween(Duration.ofMinutes(15).toSeconds() - 2, Duration.ofMinutes(15).toSeconds());
        assertThat(maxAge(refresh)).isBetween(Duration.ofDays(14).toSeconds() - 2, Duration.ofDays(14).toSeconds());
        assertThat(cookieValue(refresh)).isNotEqualTo(oldToken);

        // then: 새 Access Token으로 보호 API를 호출할 수 있다
        mockMvc.perform(get(PROTECTED_PATH).cookie(new Cookie("access_token", cookieValue(access))))
                .andExpect(status().isOk());
    }

    // 완료 조건 2: 재발급해도 절대 만료(로그인 + 30일)는 늘지 않는다
    @Test
    @DisplayName("새 Refresh 세션은 옛 세션의 sessionId·sessionExpiresAt을 그대로 이어받고, 옛 토큰 키는 지워진다")
    void inheritsSessionAndRemovesOldToken() throws Exception {
        // given
        String oldToken = loginAndGetRefreshToken("inherit");
        RefreshTokenSession oldSession = refreshTokenRepository.find(oldToken).orElseThrow();

        // when
        MockHttpServletResponse response = refreshWith(oldToken)
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        // then
        String newToken = cookieValue(setCookie(response, "refresh_token").orElseThrow());
        assertThat(refreshTokenRepository.find(newToken)).contains(oldSession);
        assertThat(refreshTokenRepository.find(oldToken)).isEmpty();
        assertThat(redisTemplate.opsForValue().get(RefreshTokenHasher.toSessionKey(oldSession.sessionId())))
                .isEqualTo(RefreshTokenHasher.hash(newToken));
        // Redis TTL은 저장 후 흐른 시간만큼 줄어 있으므로 쿠키 Max-Age와 2초 안으로 같으면 된다
        long refreshMaxAge = maxAge(setCookie(response, "refresh_token").orElseThrow());
        assertThat(redisTemplate.getExpire(RefreshTokenHasher.toKey(newToken), TimeUnit.SECONDS))
                .isBetween(refreshMaxAge - 2, refreshMaxAge);
    }

    @Test
    @DisplayName("절대 만료가 14일보다 가까우면 Refresh Max-Age와 Redis TTL이 남은 시간으로 줄어든다")
    void shrinksTtlNearAbsoluteExpiry() throws Exception {
        // given: 로그인 세션의 절대 만료를 1시간 뒤로 당긴다
        Map<String, Object> user = insertUser("near");
        String token = "near-" + UUID.randomUUID();
        Instant sessionExpiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        refreshTokenRepository.save(token, new RefreshTokenSession(
                ((Number) user.get("id")).longValue(), UUID.randomUUID(), sessionExpiresAt));

        // when
        MockHttpServletResponse response = refreshWith(token)
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        // then
        String refresh = setCookie(response, "refresh_token").orElseThrow();
        assertThat(maxAge(refresh)).isBetween(Duration.ofHours(1).toSeconds() - 5, Duration.ofHours(1).toSeconds());
        assertThat(redisTemplate.getExpire(RefreshTokenHasher.toKey(cookieValue(refresh)), TimeUnit.SECONDS))
                .isBetween(maxAge(refresh) - 2, maxAge(refresh));
    }

    @Test
    @DisplayName("만료·변조된 access_token 쿠키를 가진 채로도 재발급할 수 있다")
    void refreshesWithInvalidAccessToken() throws Exception {
        // given
        String token = loginAndGetRefreshToken("stale");

        // when & then
        refresh(new Cookie("refresh_token", token), new Cookie("access_token", "not-a-jwt"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("로그인 후 판매자로 승인되면 재발급한 Access Token의 roles에 SELLER가 들어간다")
    void reflectsSellerApproval() throws Exception {
        // given
        String token = loginAndGetRefreshToken("seller");
        Map<String, Object> reviewer = insertUser("reviewer");
        jdbcTemplate.update("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES ((SELECT id FROM users WHERE email = ?), '브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                """, email("seller"), email("seller-contact"), reviewer.get("id"));

        // when
        MockHttpServletResponse response = refreshWith(token)
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        // then
        String access = cookieValue(setCookie(response, "access_token").orElseThrow());
        assertThat(jwtProvider.parse(access).get("roles", List.class)).containsExactly("USER", "SELLER");
    }

    // 실패

    // 완료 조건 4·M00-03: 교체 직후(유예 안) 재사용은 거부하지만 세션은 유지한다
    @Test
    @DisplayName("교체된 옛 토큰을 유예 안에 다시 쓰면 401이고, 새 토큰은 계속 쓸 수 있다")
    void rejectsReuseWithinGraceButKeepsSession() throws Exception {
        // given
        String oldToken = loginAndGetRefreshToken("grace");
        String newToken = cookieValue(setCookie(refreshWith(oldToken).andReturn().getResponse(), "refresh_token")
                .orElseThrow());

        // when & then
        assertInvalidTokenWithoutCookies(refreshWith(oldToken));
        refreshWith(newToken).andExpect(status().isNoContent());
    }

    // 완료 조건 4·M00-02: 유예가 지난 재사용은 탈취로 보고 세션을 폐기한다
    @Test
    @DisplayName("교체된 옛 토큰을 유예 후에 다시 쓰면 401이고, 그 세션의 새 토큰도 폐기된다")
    void revokesSessionOnReuseAfterGrace() throws Exception {
        // given
        String oldToken = loginAndGetRefreshToken("reuse");
        String newToken = cookieValue(setCookie(refreshWith(oldToken).andReturn().getResponse(), "refresh_token")
                .orElseThrow());
        String otherToken = loginAndGetRefreshToken("other");
        Thread.sleep(REUSE_GRACE.plusMillis(300).toMillis());

        // when & then
        assertInvalidTokenWithoutCookies(refreshWith(oldToken));
        assertInvalidTokenWithoutCookies(refreshWith(newToken));
        // 다른 세션은 영향이 없다
        refreshWith(otherToken).andExpect(status().isNoContent());
    }

    // 완료 조건 2·M00-05: Redis 키가 늦게 남아 있어도 절대 만료가 지난 세션은 재발급되지 않는다
    @Test
    @DisplayName("절대 만료가 지난 세션은 키가 남아 있어도 401이고 새 토큰을 저장하지 않는다")
    void rejectsSessionPastAbsoluteExpiry() throws Exception {
        // given: 저장소를 거치지 않고 만료 시각이 지난 세션을 직접 넣는다
        Map<String, Object> user = insertUser("expired");
        String token = "expired-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(RefreshTokenHasher.toKey(token),
                "{\"userId\":" + user.get("id") + ",\"sessionId\":\"" + UUID.randomUUID()
                        + "\",\"sessionExpiresAt\":\"" + Instant.now().minusSeconds(1) + "\"}",
                Duration.ofMinutes(1));

        // when & then
        assertInvalidTokenWithoutCookies(refreshWith(token));
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toKey(token))).isTrue();
        assertThat(redisTemplate.keys("auth:refresh-token:*")).hasSize(1);
    }

    @Test
    @DisplayName("refresh_token 쿠키가 없거나 발급한 적 없는 값이면 401 INVALID_TOKEN이고 쿠키가 없다")
    void rejectsMissingOrUnknownToken() throws Exception {
        assertInvalidTokenWithoutCookies(refresh());
        assertInvalidTokenWithoutCookies(refreshWith("unknown-token"));
    }

    // M00-04: 로그인 후 정지된 회원은 재발급할 수 없고 세션도 지운다
    @Test
    @DisplayName("로그인 후 정지된 회원은 401이고 Redis의 토큰 키와 세션 키가 지워진다")
    void rejectsSuspendedUserAndRevokesSession() throws Exception {
        // given
        String token = loginAndGetRefreshToken("suspended");
        UUID sessionId = refreshTokenRepository.find(token).orElseThrow().sessionId();
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email("suspended"));

        // when & then
        assertInvalidTokenWithoutCookies(refreshWith(token));
        assertThat(refreshTokenRepository.find(token)).isEmpty();
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toSessionKey(sessionId))).isFalse();
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 403 ACCESS_DENIED이고 Refresh Token은 교체되지 않는다")
    void rejectsWithoutCsrfToken() throws Exception {
        // given
        String token = loginAndGetRefreshToken("csrf");

        // when & then
        mockMvc.perform(post(REFRESH_PATH).cookie(new Cookie("refresh_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        assertThat(refreshTokenRepository.find(token)).isPresent();
        refreshWith(token).andExpect(status().isNoContent());
    }
}
