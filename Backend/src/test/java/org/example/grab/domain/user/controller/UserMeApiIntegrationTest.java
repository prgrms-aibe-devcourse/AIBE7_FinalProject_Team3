package org.example.grab.domain.user.controller;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.AccessTokenProperties;
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
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-97 M02-02: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL로 내 정보 조회(MEMBER_AUTH 1.9)를 확인한다.
    로그인 API로 받은 access_token을 쓰고, 재발급이 필요한 판매자 승인 확인을 위해 Redis 컨테이너를 함께 띄운다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일로 회원을 만들고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class UserMeApiIntegrationTest {

    private static final String ME_PATH = "/api/v1/users/me";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
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
    private JwtProvider jwtProvider;
    @Autowired
    private AccessTokenProperties accessTokenProperties;

    private final String run = UUID.randomUUID().toString().substring(0, 6);

    // 로그인으로 받은 두 인증 쿠키 값
    private record LoginTokens(String accessToken, String refreshToken) {
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sellers WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "me-api-" + run + "-%");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "me-api-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "me-api-" + run + "-" + name + "@example.com";
    }

    private Map<String, Object> insertUser(String name) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id, public_id, created_at
                """, email(name), passwordEncoder.encode(PASSWORD), "me-" + run + "-" + name);
    }

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
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
        return new LoginTokens(cookieValue(response, "access_token"), cookieValue(response, "refresh_token"));
    }

    private static String cookieValue(MockHttpServletResponse response, String name) {
        String setCookie = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private ResultActions getMe(String accessToken) throws Exception {
        return mockMvc.perform(get(ME_PATH).cookie(new Cookie("access_token", accessToken)));
    }

    private void assertUnauthorized(ResultActions result, String code) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private String expiredAccessToken(UUID publicId) {
        Instant expiredAt = Instant.now().minus(Duration.ofMinutes(5));
        return Jwts.builder()
                .subject(publicId.toString())
                .claim("roles", List.of("USER"))
                .issuer(accessTokenProperties.issuer())
                .audience().add(accessTokenProperties.audience()).and()
                .issuedAt(Date.from(expiredAt.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiredAt))
                .signWith(Keys.hmacShaKeyFor(accessTokenProperties.secretBytes()), Jwts.SIG.HS256)
                .compact();
    }

    // 성공

    // 완료 조건 1: 명세 1.9의 일곱 필드만 담는다(GR-108 provider 추가). 휴대폰 번호·내부 id·status는 없다(AUTH-004)
    @Test
    @DisplayName("로그인한 회원은 200과 public_id·이메일·닉네임·provider·roles·profileImageUrl(null)·createdAt만 받는다")
    void returnsMyInfo() throws Exception {
        // given
        Map<String, Object> user = insertUser("ok");
        LoginTokens tokens = login("ok");

        // when
        String body = getMe(tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.get("public_id").toString()))
                .andExpect(jsonPath("$.data.email").value(email("ok")))
                .andExpect(jsonPath("$.data.nickname").value("me-" + run + "-ok"))
                .andExpect(jsonPath("$.data.provider").value("LOCAL"))
                .andExpect(jsonPath("$.data.roles", contains("USER")))
                // 등록된 이미지가 없으면 키를 생략하지 않고 null이다(M00-05)
                .andExpect(jsonPath("$.data.profileImageUrl").value((Object) null))
                .andReturn().getResponse().getContentAsString();

        // then: 명세 밖 필드가 없다
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).containsOnlyKeys(
                "userId", "email", "nickname", "provider", "roles", "profileImageUrl", "createdAt");

        // then: createdAt은 ISO 8601 문자열이고 DB 값과 같은 시각이다. 오프셋 표기는 변환하지 않는다(M00-05)
        String createdAt = JsonPath.read(body, "$.data.createdAt");
        assertThat(OffsetDateTime.parse(createdAt).toInstant())
                .isEqualTo(((OffsetDateTime) jdbcTemplate.queryForObject(
                        "SELECT created_at FROM users WHERE email = ?", OffsetDateTime.class, email("ok"))).toInstant());
    }

    // 완료 조건 2·M00-01: roles는 요청 Access Token의 권한이다. 판매자 승인은 다음 재발급부터 반영된다
    @Test
    @DisplayName("판매자로 승인돼도 기존 토큰의 roles는 USER뿐이고, 재발급한 토큰으로 조회하면 SELLER가 포함된다")
    void reflectsSellerApprovalAfterRefresh() throws Exception {
        // given
        insertUser("seller");
        LoginTokens tokens = login("seller");
        Map<String, Object> reviewer = insertUser("reviewer");
        jdbcTemplate.update("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES ((SELECT id FROM users WHERE email = ?), '브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                """, email("seller"), email("seller-contact"), reviewer.get("id"));

        // when & then: 승인 전에 발급한 토큰
        getMe(tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("USER")));

        // when & then: 재발급한 토큰(재발급은 DB로 권한을 다시 계산한다)
        String csrf = issueCsrfToken();
        MockHttpServletResponse refreshed = mockMvc.perform(post(REFRESH_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf), new Cookie("refresh_token", tokens.refreshToken()))
                        .header(XSRF_HEADER, csrf))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        getMe(cookieValue(refreshed, "access_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("USER", "SELLER")));
    }

    // GR-108: 소셜 회원은 비밀번호가 없어 로그인 API 대신 토큰을 직접 발급한다
    @Test
    @DisplayName("소셜 회원은 provider에 가입 방식(KAKAO)을 그대로 받는다")
    void returnsSocialProvider() throws Exception {
        // given
        Map<String, Object> user = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, nickname, status, provider) VALUES (?, ?, 'ACTIVE', 'KAKAO')
                RETURNING public_id
                """, email("kakao"), "me-" + run + "-kakao");
        String token = jwtProvider.issue((UUID) user.get("public_id"), Set.of(AuthRole.USER)).value();

        // when & then
        getMe(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("KAKAO"));
    }

    // 완료 조건 3: 경로·쿼리로 사용자를 고를 수 없어 각자 본인 정보만 받는다
    @Test
    @DisplayName("두 회원이 각자의 토큰으로 조회하면 각자 본인 정보만 받는다")
    void returnsOnlyOwnInfo() throws Exception {
        // given
        Map<String, Object> userA = insertUser("a");
        Map<String, Object> userB = insertUser("b");
        LoginTokens tokensA = login("a");
        LoginTokens tokensB = login("b");

        // when & then
        getMe(tokensA.accessToken())
                .andExpect(jsonPath("$.data.userId").value(userA.get("public_id").toString()))
                .andExpect(jsonPath("$.data.email").value(email("a")));
        getMe(tokensB.accessToken())
                .andExpect(jsonPath("$.data.userId").value(userB.get("public_id").toString()))
                .andExpect(jsonPath("$.data.email").value(email("b")));
    }

    // 실패

    // 완료 조건 3: 비로그인·만료·변조 토큰
    @Test
    @DisplayName("access_token 쿠키가 없으면 401 AUTHENTICATION_REQUIRED, 만료·변조 토큰이면 401 INVALID_TOKEN이다")
    void rejectsMissingOrInvalidToken() throws Exception {
        // given
        Map<String, Object> user = insertUser("invalid");

        // when & then
        assertUnauthorized(mockMvc.perform(get(ME_PATH)), "AUTHENTICATION_REQUIRED");
        assertUnauthorized(getMe(expiredAccessToken((UUID) user.get("public_id"))), "INVALID_TOKEN");
        assertUnauthorized(getMe("not-a-jwt"), "INVALID_TOKEN");
    }

    // 완료 조건 4·M00-03: 계정 상태를 드러내지 않고 회원 없음과 같은 401로 거부한다
    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("로그인 후 정지·탈퇴된 회원은 토큰이 유효해도 401 AUTHENTICATION_REQUIRED다")
    void rejectsInactiveUser(String status) throws Exception {
        // given
        insertUser("inactive");
        LoginTokens tokens = login("inactive");
        jdbcTemplate.update("UPDATE users SET status = ? WHERE email = ?", status, email("inactive"));

        // when & then
        assertUnauthorized(getMe(tokens.accessToken()), "AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원 행이 없으면 401 AUTHENTICATION_REQUIRED다")
    void rejectsDeletedUser() throws Exception {
        // given
        insertUser("deleted");
        LoginTokens tokens = login("deleted");
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email("deleted"));

        // when & then
        assertUnauthorized(getMe(tokens.accessToken()), "AUTHENTICATION_REQUIRED");
    }

    // M00-04: GET /api/v1/users/me는 USER 권한을 요구한다
    @Test
    @DisplayName("USER 권한이 없는 토큰이면 403 ACCESS_DENIED다")
    void rejectsTokenWithoutUserRole() throws Exception {
        // given
        Map<String, Object> user = insertUser("admin-only");
        String token = jwtProvider.issue((UUID) user.get("public_id"), Set.of(AuthRole.ADMIN)).value();

        // when & then
        getMe(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }
}
