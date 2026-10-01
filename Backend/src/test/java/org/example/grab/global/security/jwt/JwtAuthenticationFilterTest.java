package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.AuthenticatedUser;
import org.example.grab.global.security.jwt.InvalidAccessTokenException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// M03-04·M03-05, U3: 쿠키 추출, 제외 경로, 검증 실패 처리, Claims(sub·roles) → principal·ROLE_* 권한 변환
class JwtAuthenticationFilterTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "grab-test-only-jwt-secret-not-for-production".getBytes(StandardCharsets.UTF_8));
    private static final AccessTokenProperties PROPERTIES =
            new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);
    private static final Instant NOW = Instant.parse("2026-09-30T06:00:00Z");
    private static final UUID PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    private final JwtProvider jwtProvider = new JwtProvider(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    // commence가 받은 예외를 기록한다. 호출되지 않았으면 비어 있다
    private final List<AuthenticationException> commenced = new ArrayList<>();
    private final AuthenticationEntryPoint entryPoint = (request, response, e) -> commenced.add(e);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider, entryPoint);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 access_token 쿠키면 publicId principal과 ROLE_* 권한을 SecurityContext에 넣고 다음 필터로 넘긴다")
    void authenticatesWithValidCookie() throws Exception {
        // given
        String token = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER, AuthRole.SELLER)).value();
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", token));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(new AccessTokenPrincipal(PUBLIC_ID));
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_SELLER");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(commenced).isEmpty();
    }

    @Test
    @DisplayName("쿠키가 없으면 인증 없이 다음 필터로 넘긴다")
    void passesWithoutCookie() throws Exception {
        // given
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(apiRequest(), new MockHttpServletResponse(), chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(commenced).isEmpty();
    }

    @Test
    @DisplayName("Authorization 헤더의 토큰은 읽지 않는다")
    void ignoresAuthorizationHeader() throws Exception {
        // given
        String token = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value();
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("검증에 실패하면 SecurityContext를 비우고 AuthenticationEntryPoint로 넘기며, 다음 필터로 진행하지 않는다")
    void commencesOnInvalidToken() throws Exception {
        // given: 이전에 설정된 인증이 남아 있어도 지워야 한다
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null, "ROLE_USER"));
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", "not-a-jwt"));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNull();
        assertThat(commenced).singleElement()
                .isInstanceOfSatisfying(InvalidAccessTokenException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.MALFORMED));
    }

    @Test
    @DisplayName("값이 빈 쿠키도 INVALID_TOKEN으로 처리한다")
    void commencesOnEmptyCookieValue() throws Exception {
        // given
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", ""));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        assertThat(chain.getRequest()).isNull();
        assertThat(commenced).singleElement()
                .isInstanceOfSatisfying(InvalidAccessTokenException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.EMPTY));
    }

    @Test
    @DisplayName("/api/v1/auth/** 에서는 무효 쿠키가 있어도 필터를 건너뛴다(재발급 API가 막히지 않게)")
    void skipsAuthPaths() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setCookies(new Cookie("access_token", "not-a-jwt"));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(commenced).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    @DisplayName("roles의 각 값은 ROLE_ 접두사가 붙은 권한 하나로 바뀐다")
    void mapsEachRoleToPrefixedAuthority(AuthRole role) throws Exception {
        // given
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", jwtProvider.issue(PUBLIC_ID, Set.of(role)).value()));

        // when
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role.name());
    }

    @Test
    @DisplayName("principal은 AuthenticatedUser 계약을 지켜 identity 쪽이 JWT를 몰라도 publicId를 얻는다")
    void principalImplementsAuthenticatedUser() throws Exception {
        // given
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value()));

        // when
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(AuthenticatedUser.class,
                        user -> assertThat(user.publicId()).isEqualTo(PUBLIC_ID));
    }

    @Test
    @DisplayName("서명은 맞아도 sub가 UUID가 아니거나 없으면 거부한다")
    void rejectsNonUuidSubject() throws Exception {
        assertCommencedWithClaimReason(signed("user-7", List.of("USER")));
        assertCommencedWithClaimReason(signed("", List.of("USER")));
        // users.id 같은 내부 숫자 ID를 sub로 넣은 경우
        assertCommencedWithClaimReason(signed("7", List.of("USER")));
        assertCommencedWithClaimReason(signed(null, List.of("USER")));
    }

    @Test
    @DisplayName("UUID로 해석되더라도 표준 소문자 36자 형식이 아니면 거부한다")
    void rejectsNonCanonicalUuidSubject() throws Exception {
        // UUID.fromString은 둘 다 받아들인다
        assertCommencedWithClaimReason(signed("1-1-1-1-1", List.of("USER")));
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString().toUpperCase(), List.of("USER")));
    }

    @Test
    @DisplayName("서명은 맞아도 roles가 없거나 모르는 값이면 거부한다")
    void rejectsMissingOrUnknownRoles() throws Exception {
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), null));
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), List.of()));
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), List.of("SUPER_ADMIN")));
        // 접두사가 붙은 값은 Claim 형식이 아니다(JWT에는 접두사 없이 넣는다)
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), List.of("ROLE_USER")));
        // 알려진 값 사이에 모르는 값이 하나라도 섞이면 일부만 인정하지 않고 통째로 거부한다
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), List.of("USER", "SUPER_ADMIN")));
    }

    @Test
    @DisplayName("서명은 맞아도 roles가 목록이 아니거나 문자열이 아닌 요소가 있으면 거부한다")
    void rejectsMalformedRoles() throws Exception {
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), "USER"));
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), List.of(1)));
        assertCommencedWithClaimReason(signed(PUBLIC_ID.toString(), Arrays.asList("USER", null)));
    }

    private void assertCommencedWithClaimReason(String token) throws Exception {
        commenced.clear();
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new Cookie("access_token", token));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNull();
        assertThat(commenced).singleElement()
                .isInstanceOfSatisfying(InvalidAccessTokenException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.CLAIM));
    }

    // 같은 키·iss·aud로 서명해 검증은 통과하지만 sub·roles만 원하는 값으로 만든 토큰
    private static String signed(String subject, Object roles) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuer("grab")
                .audience().add("grab-api").and()
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))));
        if (roles != null) {
            builder.claim(JwtProvider.ROLES_CLAIM, roles);
        }
        return builder.signWith(Keys.hmacShaKeyFor(PROPERTIES.secretBytes()), Jwts.SIG.HS256).compact();
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("GET", "/api/v1/orders");
    }
}
