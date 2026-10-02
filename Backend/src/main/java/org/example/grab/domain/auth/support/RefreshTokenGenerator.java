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
    // 무작위 바이트를 문자열로 바꾸는 변환 도구를 생성
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        // 32바이트 공간 생성
        byte[] bytes = new byte[TOKEN_BYTES];
        // 바이트 배열에 무작위 값 채움
        secureRandom.nextBytes(bytes);
        // 배열을 43자 Base64URL 문자열로 변환해서 반환
        return ENCODER.encodeToString(bytes);
    }
}
