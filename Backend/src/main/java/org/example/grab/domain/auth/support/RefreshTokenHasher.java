package org.example.grab.domain.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/*
    Refresh Token 원문을 해시하고 Redis 키를 만든다(GR-33 M00-02, M00-03).
    호출하는 쪽마다 해시하면 원문을 키에 넣거나 방식이 어긋날 수 있어 저장소만 이 클래스를 쓴다(M00-07).
    원문이 256비트 난수라 대입이 불가능하므로 HMAC 없이 SHA-256만으로 충분하다.
 */
public final class RefreshTokenHasher {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String KEY_PREFIX = "auth:refresh-token:";

    private RefreshTokenHasher() {
    }

    public static String toKey(String rawToken) {
        return KEY_PREFIX + hash(rawToken);
    }

    public static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("해시할 Refresh Token은 필수입니다.");
        }

        try {
            // MessageDigest는 스레드 안전하지 않아 호출마다 새로 만든다
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            // 해시는 문자열이 아니라 바이트에 대해 계산하므로, 문자열을 바이트로 변경
            // 입력 바이트 전체의 SHA-256을 계산해 32바이트 배열 저장
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.US_ASCII));
            // 32 바이트를 한 바이트당 hex 두 글자로 바꿔 64자 문자열 생성 -> 해시 바이트를 그대로는 출력할 수 없는 값이 섞여 있기 때문에
            // Redis 키 문자열로 쓰기 어렵기 때문에 hex로 변환
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
