package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.example.grab.global.security.AuthRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// M03-01: JwtProvider.parse의 서명·iss·aud·exp(leeway) 검증
class JwtProviderParseTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "grab-test-only-jwt-secret-not-for-production".getBytes(StandardCharsets.UTF_8));
    private static final AccessTokenProperties PROPERTIES =
            new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);
    private static final SecretKey KEY = Keys.hmacShaKeyFor(PROPERTIES.secretBytes());
    // 이 시각에 발급하면 exp는 06:15:00이다
    private static final Instant ISSUED_AT = Instant.parse("2026-09-30T06:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-30T06:15:00Z");
    private static final UUID PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    private final JwtProvider issuer = providerAt(ISSUED_AT);

    @Test
    @DisplayName("발급한 토큰은 검증을 통과하고 Claim을 그대로 돌려준다")
    void parsesIssuedToken() {
        // given
        String token = issuer.issue(PUBLIC_ID, Set.of(AuthRole.USER, AuthRole.SELLER)).value();

        // when
        Claims claims = issuer.parse(token);

        // then
        assertThat(claims.getSubject()).isEqualTo(PUBLIC_ID.toString());
        assertThat(claims.get(JwtProvider.ROLES_CLAIM, List.class)).containsExactly("USER", "SELLER");
    }

    @Test
    @DisplayName("페이로드를 바꾸면 서명이 맞지 않아 거부한다")
    void rejectsTamperedPayload() {
        // given: 서명은 그대로 두고 roles만 ADMIN으로 바꾼다
        String token = issuer.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value();
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"USER\"", "\"ADMIN\"");
        String tampered = parts[0] + "." + base64Url(payload) + "." + parts[2];

        // when, then
        assertThatThrownBy(() -> issuer.parse(tampered)).isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 거부한다")
    void rejectsTokenSignedWithAnotherKey() {
        // given
        String token = baseClaims().signWith(Jwts.SIG.HS256.key().build(), Jwts.SIG.HS256).compact();

        // when, then
        assertThatThrownBy(() -> issuer.parse(token)).isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("iss가 다르면 거부한다")
    void rejectsWrongIssuer() {
        // given
        String token = baseClaims().issuer("other").signWith(KEY, Jwts.SIG.HS256).compact();

        // when, then
        assertThatThrownBy(() -> issuer.parse(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    @DisplayName("aud에 grab-api가 없으면 거부한다")
    void rejectsWrongAudience() {
        // given
        String token = Jwts.builder()
                .subject(PUBLIC_ID.toString())
                .issuer("grab")
                .audience().add("other-api").and()
                .expiration(Date.from(EXPIRES_AT))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        // when, then
        assertThatThrownBy(() -> issuer.parse(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    @DisplayName("만료 후 leeway(30초) 안에서는 통과하고, 넘으면 거부한다")
    void rejectsExpiredTokenAfterClockSkew() {
        // given
        String token = issuer.issue(PUBLIC_ID, Set.of(AuthRole.USER)).value();
        JwtProvider withinSkew = providerAt(EXPIRES_AT.plusSeconds(29));
        JwtProvider beyondSkew = providerAt(EXPIRES_AT.plusSeconds(31));

        // when, then
        assertThat(withinSkew.parse(token).getSubject()).isEqualTo(PUBLIC_ID.toString());
        assertThatThrownBy(() -> beyondSkew.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("서명 없는 토큰(alg: none)은 거부한다")
    void rejectsUnsignedToken() {
        // given: signWith를 호출하지 않으면 서명 없는 토큰이 만들어진다
        String token = baseClaims().compact();

        // when, then
        assertThatThrownBy(() -> issuer.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("같은 키로 서명했어도 HS256이 아닌 알고리즘(HS384·HS512)은 거부한다")
    void rejectsAlgorithmOtherThanHs256() {
        // given: 64바이트 키는 HS256·HS384·HS512 모두에 쓸 수 있어, 알고리즘을 고정하지 않으면 셋 다 서명 검증을 통과한다
        String secret64 = Base64.getEncoder().encodeToString("k".repeat(64).getBytes(StandardCharsets.UTF_8));
        AccessTokenProperties properties = new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", secret64);
        SecretKey key64 = Keys.hmacShaKeyFor(properties.secretBytes());
        JwtProvider provider = new JwtProvider(properties, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        String hs256 = baseClaims().signWith(key64, Jwts.SIG.HS256).compact();
        String hs384 = baseClaims().signWith(key64, Jwts.SIG.HS384).compact();
        String hs512 = baseClaims().signWith(key64, Jwts.SIG.HS512).compact();

        // when, then: 허용 목록에 없는 알고리즘은 jjwt가 SignatureException으로 거부한다
        assertThat(provider.parse(hs256).getSubject()).isEqualTo(PUBLIC_ID.toString());
        assertThatThrownBy(() -> provider.parse(hs384)).isInstanceOf(SignatureException.class);
        assertThatThrownBy(() -> provider.parse(hs512)).isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("JWT 형식이 아닌 값은 거부한다")
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> issuer.parse("not-a-jwt")).isInstanceOf(MalformedJwtException.class);
    }

    // iss·aud·exp가 정상인 Claim. 테스트마다 바꿀 부분만 덮어쓴다
    private static JwtBuilder baseClaims() {
        return Jwts.builder()
                .subject(PUBLIC_ID.toString())
                .issuer("grab")
                .audience().add("grab-api").and()
                .expiration(Date.from(EXPIRES_AT));
    }

    private static JwtProvider providerAt(Instant now) {
        return new JwtProvider(PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
