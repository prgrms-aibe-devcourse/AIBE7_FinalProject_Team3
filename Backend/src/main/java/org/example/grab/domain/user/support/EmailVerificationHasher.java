package org.example.grab.domain.user.support;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/*
    이메일 인증의 이메일·코드를 HMAC-SHA256으로 해시한다(GR-61 M00-01).
    6자리 코드는 경우의 수가 100만 개라 SHA-256만 쓰면 Redis 값을 본 사람이 전부 대입해 원문을 알 수 있다.
    이메일도 키에 원문을 남기지 않되 사전 대입으로 되찾을 수 없도록 같은 키로 HMAC한다.
    키 하나를 두 용도에 쓰므로 입력 앞에 용도를 붙여 이메일 해시와 코드 해시가 겹치지 않게 한다.
 */
@Component
public class EmailVerificationHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String EMAIL_PREFIX = "email:";
    private static final String CODE_PREFIX = "code:";

    private final SecretKeySpec key;

    public EmailVerificationHasher(EmailVerificationProperties properties) {
        this.key = new SecretKeySpec(properties.hmacSecretBytes(), ALGORITHM);
    }

    // 정규화(EmailNormalizer)한 이메일을 받는다. 정규화 전 값을 넘기면 같은 주소가 다른 키가 된다
    public String hashEmail(String email) {
        return hmac(EMAIL_PREFIX, email, "이메일");
    }

    public String hashVerificationCode(String code) {
        return hmac(CODE_PREFIX, code, "인증 코드");
    }

    // 일치하는 앞부분 길이로 코드를 추측할 수 없도록 상수 시간으로 비교한다(M03-03)
    public boolean matchesVerificationCode(String code, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        byte[] expected = storedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hashVerificationCode(code).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmac(String prefix, String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("해시할 " + name + "은(는) 필수입니다.");
        }

        try {
            // Mac은 스레드 안전하지 않아 호출마다 새로 만든다
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] hashed = mac.doFinal((prefix + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256을 사용할 수 없습니다.", exception);
        }
    }
}
