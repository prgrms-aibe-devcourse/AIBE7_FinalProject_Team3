package org.example.grab.domain.user.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/*
    이메일 인증 설정(GR-61 M00-01). 값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    hmacSecret은 기본값 없이 환경변수 EMAIL_VERIFICATION_SECRET으로만 주입하며, 형식은 JWT_SECRET과 같다.
 */
@ConfigurationProperties(prefix = "grab.auth.email-verification")
public record EmailVerificationProperties(
        // Base64로 인코딩한 HMAC-SHA256 키. 디코딩한 값이 32바이트(256비트) 이상이어야 한다
        String hmacSecret
) {

    private static final int MIN_SECRET_BYTES = 32;

    public EmailVerificationProperties(String hmacSecret) {
        validateSecret(hmacSecret);
        this.hmacSecret = hmacSecret;
    }

    // 받은 쪽이 배열을 지우거나 바꿔도 설정의 키가 그대로이도록 호출할 때마다 새로 디코딩한다
    public byte[] hmacSecretBytes() {
        return Base64.getDecoder().decode(hmacSecret);
    }

    /*
        오류 메시지에는 키 값을 넣지 않고 길이만 알린다.
        Base64 디코더의 예외 메시지는 잘못된 문자를 포함하므로 cause로 연결하지 않는다.
     */
    private static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "grab.auth.email-verification.hmac-secret(EMAIL_VERIFICATION_SECRET) 값은 비어 있을 수 없습니다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "grab.auth.email-verification.hmac-secret(EMAIL_VERIFICATION_SECRET) 값은 Base64 형식이어야 합니다.");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("grab.auth.email-verification.hmac-secret(EMAIL_VERIFICATION_SECRET) 값은 디코딩 후 "
                    + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. 현재 " + decoded.length + "바이트");
        }
    }

    // record 기본 toString()은 키를 그대로 출력하므로 항상 같은 값으로 가린다
    @Override
    public String toString() {
        return "EmailVerificationProperties[hmacSecret=masked]";
    }
}
