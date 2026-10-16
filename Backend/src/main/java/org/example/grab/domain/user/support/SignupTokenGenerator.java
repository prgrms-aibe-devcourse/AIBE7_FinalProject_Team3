package org.example.grab.domain.user.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/*
    가입 컨텍스트 토큰 원문을 만든다(GR-61 M02-03). LOCAL 가입(email_signup_token)과 소셜 가입(oauth2_signup_token, GR-46)이 함께 쓴다.
    두 토큰은 "추측할 수 없는 일회용 가입 토큰"이라는 같은 요구라 형식을 나누지 않는다. 서로의 토큰으로 가입할 수 없는 것은
    쿠키(이름·Path)와 저장소 키 접두사가 다르기 때문이다.
    RefreshTokenGenerator와 같은 방식이다. 정보를 담지 않는 256비트 난수라 추측할 수 없고, 서버는 SHA-256 해시만 저장한다.
    auth 도메인의 Refresh Token 클래스를 가져다 쓰면 두 토큰의 형식이 함께 묶이므로 따로 둔다.
 */
@Component
public class SignupTokenGenerator {

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
