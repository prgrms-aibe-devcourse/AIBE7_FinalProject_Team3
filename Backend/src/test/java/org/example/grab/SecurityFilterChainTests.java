package org.example.grab;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.AccessTokenProperties;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// M03-06·07, S2~S6: SecurityConfig가 세션·폼 로그인 없이 JWT 필터 하나로 인증하고, 실패를 공통 오류 형식으로 응답하는지 실제 필터 체인으로 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityFilterChainTests {

    // 인증되면 보안을 통과해 매핑 없는 경로의 404까지 간다. 막히면 401이다
    private static final String UNMAPPED_PROTECTED_PATH = "/api/v1/security-filter-chain-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private AccessTokenProperties accessTokenProperties;

    @Test
    @DisplayName("유효한 access_token 쿠키는 보호 경로의 인증을 통과한다")
    void authenticatesWithValidCookie() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("유효한 JWT도 Authorization Bearer 헤더만 보내면 인증되지 않는다")
    void rejectsBearerHeaderWithoutCookie() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Bearer 헤더의 잘못된 토큰도 검증하지 않아 INVALID_TOKEN이 아니라 AUTHENTICATION_REQUIRED")
    void doesNotParseBearerHeader() throws Exception {
        // 헤더를 읽었다면 INVALID_TOKEN이 나왔을 것이다
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("유효한 쿠키가 있으면 Bearer 헤더에 잘못된 값이 있어도 쿠키로만 인증한다")
    void ignoresBearerHeaderWhenCookiePresent() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH)
                        .cookie(new Cookie("access_token", token))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("쿠키 없이 보호 경로에 접근하면 공통 오류 형식의 401 AUTHENTICATION_REQUIRED")
    void rejectsMissingCookie() throws Exception {
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("변조된 access_token 쿠키는 401 INVALID_TOKEN이고 응답 본문에 토큰이 없다")
    void rejectsInvalidCookie() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        // when, then
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", tampered)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                .andExpect(content().string(not(containsString(tampered.substring(0, 20)))));
    }

    @Test
    @DisplayName("공개 경로라도 잘못된 쿠키는 익명으로 통과시키지 않고 401 INVALID_TOKEN")
    void rejectsInvalidCookieOnPublicPath() throws Exception {
        mockMvc.perform(get("/api/v1/categories").cookie(new Cookie("access_token", "not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("인증 실패 응답에 세션 쿠키를 만들지 않는다")
    void doesNotCreateSession() throws Exception {
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("폼 로그인·Basic 인증이 없어 /login 페이지와 Basic 헤더는 인증 수단이 아니다")
    void disablesFormLoginAndHttpBasic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    // TODO(GR-44): CSRF를 다시 켜면 이 테스트는 403을 기대하도록 바꾼다
    @Test
    @DisplayName("CSRF 임시 해제: 토큰 없는 비로그인 PUT은 403이 아니라 401")
    void csrfTemporarilyDisabled() throws Exception {
        mockMvc.perform(put("/api/v1/drops/1/wish"))
                .andExpect(status().isUnauthorized());
    }

    // S4: /api/v1/auth/**는 필터를 건너뛰어, 무효 쿠키를 가진 사용자도 재발급·로그인을 호출할 수 있다(M00-08)
    @Test
    @DisplayName("만료된 쿠키를 가진 채 /api/v1/auth/**로 요청해도 토큰 검증 401(INVALID_TOKEN)이 나지 않는다")
    void skipsTokenValidationOnAuthPaths() throws Exception {
        // given
        String expired = expiredToken();

        // when, then: 같은 쿠키라도 일반 보호 경로에서는 토큰 검증에 걸린다
        mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        // auth 경로는 아직 permitAll이 아니라(GR-30·34에서 연다) 익명 요청으로 인가 단계에서 막힌다.
        // 토큰 검증 실패(INVALID_TOKEN)가 아니라 인증 없음(AUTHENTICATION_REQUIRED)이면 필터를 건너뛴 것이다
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("access_token", expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    // S5: 공개 경로 유지와 판매자 API 권한
    @Test
    @DisplayName("Actuator health는 인증 없이 접근된다")
    void exposesActuatorHealthWithoutAuthentication() throws Exception {
        // health 상태(UP·DOWN)는 DB·Redis 연결에 따라 달라지므로 인증·인가에 막히지 않는지만 본다
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    @DisplayName("USER만 가진 토큰으로 판매자 API에 접근하면 403 ACCESS_DENIED")
    void rejectsUserTokenOnSellerApi() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then: ROLE_SELLER가 없어 URL 규칙에서 거부된다(컨트롤러·DB 조회 이전)
        mockMvc.perform(get("/api/v1/seller/drops").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    // GR-64: 판매자 경로 권한은 컨트롤러가 아니라 필터에서 먼저 걸러야 한다.
    @Test
    @DisplayName("쿠키 없이 /api/v1/seller/drops는 401 AUTHENTICATION_REQUIRED")
    void rejectsUnauthenticatedSellerApi() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("USER 토큰 + 검증에 실패하는 body로 취소 API를 호출하면 400이 아니라 403 ACCESS_DENIED")
    void rejectsUserTokenBeforeValidationOnSellerApi() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then: @Valid보다 인가가 먼저 실행되어 body 검증 결과가 노출되지 않는다
        mockMvc.perform(post("/api/v1/seller/drops/1/cancel")
                        .cookie(new Cookie("access_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("공개 재고 재조회는 인증 쿠키 없이 보안을 통과한다")
    void allowsPublicStocksWithoutAuthentication() throws Exception {
        // 매핑된 공개 경로라 인증 없이 통과한다. 없는 DROP이면 404 DROP_NOT_FOUND(401·403이 아님)
        mockMvc.perform(get("/api/v1/drops/1/stocks"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    @DisplayName("WISH 등 기존 보호 API는 여전히 인증을 요구한다")
    void stillProtectsWishApi() throws Exception {
        mockMvc.perform(get("/api/v1/drops/1/wish"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("쿠키 없이 판매자 재고 현황은 401 AUTHENTICATION_REQUIRED")
    void rejectsUnauthenticatedSellerStocks() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops/1/stocks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("USER만 가진 토큰으로 판매자 재고 현황에 접근하면 403 ACCESS_DENIED")
    void rejectsUserTokenOnSellerStocks() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then: ROLE_SELLER가 없어 URL 규칙에서 거부된다
        mockMvc.perform(get("/api/v1/seller/drops/1/stocks").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("USER 토큰은 판매자 경로 전체에서 403 (주문·대시보드 포함)")
    void rejectsUserTokenOnAllSellerPaths() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get("/api/v1/seller/orders").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("USER 토큰으로 /api/v1/seller-applications에 접근해도 401·403이 아니다")
    void doesNotApplySellerRuleToSellerApplications() throws Exception {
        // given
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then: 경로 세그먼트 매칭이라 판매자 신청 API는 URL 규칙에 걸리지 않는다
        mockMvc.perform(get("/api/v1/seller-applications").cookie(new Cookie("access_token", token)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // S6: 토큰 원문은 응답 본문에도 로그에도 남지 않는다
    @Test
    @DisplayName("토큰 검증에 실패해도 응답 본문과 로그에 토큰 원문이 없고, 로그에는 사유만 남는다")
    void doesNotExposeTokenInResponseOrLogs(CapturedOutput output) throws Exception {
        // given: 서명만 틀린 토큰. 헤더·페이로드는 진짜라 로그에 남으면 바로 드러난다
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();
        String[] parts = token.split("\\.");
        char[] signature = parts[2].toCharArray();
        signature[10] = signature[10] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);

        // when
        String body = mockMvc.perform(get(UNMAPPED_PROTECTED_PATH).cookie(new Cookie("access_token", tampered)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // then
        assertThat(body).doesNotContain(parts[0]).doesNotContain(parts[1]).doesNotContain("SIGNATURE");
        assertThat(output.getAll())
                .contains("Access Token 거부: reason=SIGNATURE")
                .doesNotContain(parts[0])
                .doesNotContain(parts[1]);
    }

    // 테스트 키·iss·aud로 서명했지만 exp가 leeway(30초)보다 오래전에 지난 토큰
    private String expiredToken() {
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
}
