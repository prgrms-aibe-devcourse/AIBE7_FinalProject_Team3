package org.example.grab.domain.user.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/*
    가입 컨텍스트 토큰 원문을 만든다(GR-61 M02-03). email_signup_token 쿠키에 그대로 담긴다.
    RefreshTokenGenerator와 같은 방식이다. 정보를 담지 않는 256비트 난수라 추측할 수 없고, 서버는 SHA-256 해시만 저장한다.
    auth 도메인의 Refresh Token 클래스를 가져다 쓰면 두 토큰의 형식이 함께 묶이므로 따로 둔다.
 */
@Component
public class EmailSignupTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom secureRandom = new SecureRandom();

    // 쿠키에 그대로 넣을 수 있는 Base64URL(패딩 없음) 43자
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES]; // 32바이트 공간 생성
        secureRandom.nextBytes(bytes); // 배열을 안전한 난수로 채움
        return ENCODER.encodeToString(bytes); // 난수를 문자열로 변환
    }
}
