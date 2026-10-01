package org.example.grab.domain.auth.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/*
    Refresh Token 원문(opaque token)을 만든다(GR-33 M00-02).
    정보를 담지 않는 256비트 난수라 추측할 수 없고, 서버는 원문 대신 SHA-256 해시만 저장한다.
    결과는 쿠키에 그대로 넣을 수 있는 Base64URL(패딩 없음) 43자다.
 */
@Component
public class RefreshTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    // 생성할 때마다 운영체제에서 시드를 다시 받지 않도록 하나를 재사용한다. 여러 스레드가 함께 써도 안전하다
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
