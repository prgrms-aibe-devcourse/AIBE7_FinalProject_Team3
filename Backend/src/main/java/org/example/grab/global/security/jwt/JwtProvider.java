package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.grab.global.security.AuthRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/*
    Access Token(JWT) 발급과 검증. Claim은 sub, roles, iss, aud, iat, exp, jti만 넣는다(GR-32 완료 조건).
    내부 ID(users.id)·sellerId·개인정보·비밀값은 넣지 않는다.
    토큰 원문과 서명 키는 로그에 남기지 않는다.
 */
@Component
public class JwtProvider {

    static final String ROLES_CLAIM = "roles";
    // 단일 서버라 발급·검증 시계가 같아 작게 둔다(M00-06)
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final AccessTokenProperties properties;
    private final SecretKey key;
    private final Clock clock;
    private final JwtParser parser;

    @Autowired
    public JwtProvider(AccessTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtProvider(AccessTokenProperties properties, Clock clock) {
        this.properties = properties;
        // 서버 기동 시 한 번만 만든다. 발급과 검증(M03)이 같은 키를 쓴다(HS256)
        // Keys.hmacShaKeyFor() 사용해서 secret 바이트 값을 signWith(), verifyWith()에 사용되야 하는 SecretKey 객체로 변환
        this.key = Keys.hmacShaKeyFor(properties.secretBytes());
        this.clock = clock;
        // 파서는 불변이고 여러 요청이 동시에 써도 안전하므로 한 번만 만든다
        this.parser = Jwts.parser()
                // 헤더의 alg를 믿지 않고 HS256만 허용한다. 키가 64바이트 이상이면 같은 키로 서명한
                // HS384·HS512 토큰도 서명 검증을 통과하므로, 허용 목록을 비우고 HS256 하나만 둔다
                .sig().clear().add(Jwts.SIG.HS256).and()
                .verifyWith(key) // 서명 확인에 쓸 키
                .requireIssuer(properties.issuer()) // Issuer 검증
                .requireAudience(properties.audience()) // Audience 검증
                .clockSkewSeconds(CLOCK_SKEW.toSeconds())
                // 만료 판단도 발급과 같은 시계를 쓴다. 테스트에서 고정 시각으로 만료를 확인할 수 있다
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    // 토큰 생성기
    public IssuedAccessToken issue(UUID publicId, Set<AuthRole> roles) {
        // publicId가 null이거나 역할이 아무것도 없다면 예외를 던짐
        Objects.requireNonNull(publicId, "publicId");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("roles는 비어 있을 수 없습니다.");
        }
        // JWT의 iat·exp는 초 단위라, 돌려주는 만료 시각이 exp Claim과 같도록 초 미만을 버린다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.ttl());

        String token = Jwts.builder()
                .subject(publicId.toString()) // 사용자 식별 정보
                .claim(ROLES_CLAIM, roleNames(roles))
                .issuer(properties.issuer())
                // .audience()를 호출하면 토큰 빌더에서 잠시 "aud 목록 편집기"로 넘어가고, .and()를 통해 편집기를 닫고 다시 토큰 빌더로 돌아오게 함
                .audience().add(properties.audience()).and()
                // jjwt의 issuadAt()과 expiration()이 java.util.Date 타입만 받기 때문에 Date.from으로 형 변환
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                // 저장하지 않는다. 토큰 원문 대신 로그 추적에 쓴다
                .id(UUID.randomUUID().toString())
                // 생략하면 키 길이에 따라 HS384·HS512가 선택될 수 있으므로 검증과 같은 HS256을 명시한다
                .signWith(key, Jwts.SIG.HS256) // 서명 생성
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    /*
        알고리즘(HS256만), 서명(같은 키로 다시 계산해 비교), iss, aud, exp(leeway 30초)를 검증하고 Claim을 돌려준다.
        parseSignedClaims는 서명이 없는 토큰(alg: none)을 거부한다.
        실패하면 jjwt의 JwtException 계열 예외가 그대로 나간다. INVALID_TOKEN 변환(M03-03)은 이어서 추가한다.
     */
    public Claims parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    // 입력 Set의 순서와 관계없이 같은 권한이면 같은 Claim이 나오도록 enum 선언 순서로 정렬한다
    private static List<String> roleNames(Set<AuthRole> roles) {
        return roles.stream()
                .sorted()
                .map(AuthRole::name)
                .toList();
    }
}
