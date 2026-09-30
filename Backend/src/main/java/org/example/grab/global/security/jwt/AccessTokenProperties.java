package org.example.grab.global.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Base64;

/*
    Access Token(JWT) 발급·검증 설정. 값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    secret은 기본값 없이 환경변수 JWT_SECRET으로만 주입하며, 환경마다 다른 키를 쓴다.
 */
@ConfigurationProperties(prefix = "grab.auth.access-token")
public record AccessTokenProperties(
        Duration ttl,
        String issuer,
        String audience,
        // Base64로 인코딩한 HS256 서명 키. 디코딩한 값이 32바이트(256비트) 이상이어야 한다
        String secret
) {

    private static final int MIN_SECRET_BYTES = 32;

    public AccessTokenProperties(Duration ttl, String issuer, String audience, String secret) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("grab.auth.access-token.ttl 값은 0보다 커야 합니다.");
        }
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        validateSecret(secret);
        this.ttl = ttl;
        this.issuer = issuer;
        this.audience = audience;
        this.secret = secret;
    }

    // JWT_SECRET을 디코딩한 서명 키 바이트. byte[]는 받은 쪽에서 수정할 수 있으므로
    // 호출할 때마다 새로 디코딩한 배열을 돌려줘서 받은 배열을 지우거나 바꿔도 설정의 키는 그대로다
    public byte[] secretBytes() {
        return Base64.getDecoder().decode(secret);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("grab.auth.access-token." + name + " 값은 비어 있을 수 없습니다.");
        }
    }

    /*
        오류 메시지에는 키 값을 넣지 않고 길이만 알린다.
        Base64 디코더의 예외 메시지는 잘못된 문자를 포함하므로 cause로 연결하지 않는다.
     */
    private static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("grab.auth.access-token.secret(JWT_SECRET) 값은 비어 있을 수 없습니다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("grab.auth.access-token.secret(JWT_SECRET) 값은 Base64 형식이어야 합니다.");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("grab.auth.access-token.secret(JWT_SECRET) 값은 디코딩 후 "
                    + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. 현재 " + decoded.length + "바이트");
        }
    }

    /*
        record가 자동으로 만드는 toString()은 모든 필드를 출력해 로그·예외 메시지·디버거에 서명 키가 남는다.
        키는 null·길이 여부도 드러나지 않도록 항상 같은 값으로 가린다.
     */
    @Override
    public String toString() {
        return "AccessTokenProperties[ttl=" + ttl + ", issuer=" + issuer + ", audience=" + audience + ", secret=masked]";
    }
}
