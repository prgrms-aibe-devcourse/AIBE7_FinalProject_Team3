package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.grab.global.security.AuthRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/*
    Access Token(JWT) 발급. Claim은 sub, roles, iss, aud, iat, exp, jti만 넣는다(GR-32 완료 조건).
    내부 ID(users.id)·sellerId·개인정보·비밀값은 넣지 않는다.
    토큰 원문과 서명 키는 로그에 남기지 않는다.
 */
@Component
public class JwtProvider {

    static final String ROLES_CLAIM = "roles";

    private final AccessTokenProperties properties;
    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public JwtProvider(AccessTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtProvider(AccessTokenProperties properties, Clock clock) {
        this.properties = properties;
        // 서버 기동 시 한 번만 만든다. 발급과 검증(M03)이 같은 키를 쓴다(HS256)
        this.key = Keys.hmacShaKeyFor(properties.secretBytes());
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID publicId, Set<AuthRole> roles) {
        Objects.requireNonNull(publicId, "publicId");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("roles는 비어 있을 수 없습니다.");
        }
        // JWT의 iat·exp는 초 단위라, 돌려주는 만료 시각이 exp Claim과 같도록 초 미만을 버린다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.ttl());

        String token = Jwts.builder()
                .subject(publicId.toString())
                .claim(ROLES_CLAIM, roleNames(roles))
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                // 저장하지 않는다. 토큰 원문 대신 로그 추적에 쓴다
                .id(UUID.randomUUID().toString())
                // 생략하면 키 길이에 따라 HS384·HS512가 선택될 수 있으므로 검증과 같은 HS256을 명시한다
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    // 입력 Set의 순서와 관계없이 같은 권한이면 같은 Claim이 나오도록 enum 선언 순서로 정렬한다
    private static List<String> roleNames(Set<AuthRole> roles) {
        return roles.stream()
                .sorted()
                .map(AuthRole::name)
                .toList();
    }
}
