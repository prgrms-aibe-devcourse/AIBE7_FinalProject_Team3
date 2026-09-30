package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.grab.global.security.AuthRole;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "grab-test-only-jwt-secret-not-for-production".getBytes(StandardCharsets.UTF_8));
    private static final AccessTokenProperties PROPERTIES =
            new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);
    // 초 미만 값을 넣어 iat·exp가 초 단위로 내림되는지 함께 확인한다
    private static final Instant NOW = Instant.parse("2026-09-30T06:00:00.789Z");
    private static final UUID PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    private final JwtProvider jwtProvider = new JwtProvider(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("Claim은 sub·roles·iss·aud·iat·exp·jti 7개뿐이고 값이 설정과 입력대로다")
    void issuesTokenWithExactlySevenClaims() {
        // when
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER));

        // then
        Claims claims = parse(issued.value()).getPayload();
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "roles", "iss", "aud", "iat", "exp", "jti");
        assertThat(claims.getSubject()).isEqualTo(PUBLIC_ID.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("USER");
        assertThat(claims.getIssuer()).isEqualTo("grab");
        assertThat(claims.getAudience()).containsExactly("grab-api");
        assertThat(claims.getId()).satisfies(jti -> UUID.fromString(jti));
    }

    @Test
    @DisplayName("헤더 알고리즘은 HS256이다")
    void signsWithHs256() {
        // when
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER));

        // then
        assertThat(parse(issued.value()).getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    @DisplayName("iat는 발급 시각(초 단위), exp는 iat + ttl이고, 돌려주는 만료 시각이 exp와 같다")
    void setsIssuedAtAndExpiration() {
        // given
        Instant expectedIssuedAt = Instant.parse("2026-09-30T06:00:00Z");
        Instant expectedExpiresAt = Instant.parse("2026-09-30T06:15:00Z");

        // when
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER));

        // then
        Claims claims = parse(issued.value()).getPayload();
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(expectedIssuedAt);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(expectedExpiresAt);
        assertThat(issued.expiresAt()).isEqualTo(expectedExpiresAt);
    }

    @Test
    @DisplayName("jti는 발급할 때마다 다르다")
    void generatesDifferentJtiEachTime() {
        // when
        String first = parse(jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value()).getPayload().getId();
        String second = parse(jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value()).getPayload().getId();

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("roles는 입력 순서와 관계없이 USER·SELLER·ADMIN 선언 순서로 담긴다")
    void ordersRolesByDeclaration() {
        // when
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, EnumSet.of(AuthRole.ADMIN, AuthRole.USER, AuthRole.SELLER));

        // then
        assertThat(parse(issued.value()).getPayload().get("roles", List.class))
                .containsExactly("USER", "SELLER", "ADMIN");
    }

    @Test
    @DisplayName("publicId가 없거나 roles가 비어 있으면 발급하지 않는다")
    void rejectsMissingInput() {
        assertThatThrownBy(() -> jwtProvider.issue(null, Set.of(AuthRole.USER)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> jwtProvider.issue(PUBLIC_ID, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtProvider.issue(PUBLIC_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다른 키로는 서명 검증에 실패한다")
    void cannotBeVerifiedWithAnotherKey() {
        // given
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER));

        // when, then
        assertThatThrownBy(() -> Jwts.parser()
                .verifyWith(Jwts.SIG.HS256.key().build())
                .build()
                .parseSignedClaims(issued.value()))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("발급 결과의 toString은 토큰 원문을 가린다")
    void masksTokenInToString() {
        // when
        IssuedAccessToken issued = jwtProvider.issue(PUBLIC_ID, Set.of(AuthRole.USER));

        // then
        assertThat(issued.toString()).doesNotContain(issued.value()).contains("value=masked");
    }

    // 검증기(M03) 없이 발급 결과만 확인하려고 같은 키로 직접 파싱한다. 만료 검사는 고정 시각 기준으로 한다
    private static Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(PROPERTIES.secretBytes()))
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token);
    }
}
