package org.example.grab.domain.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/*
    Refresh Token 원문을 해시하고 Redis 키를 만든다(GR-33 M00-02, M00-03).
    호출하는 쪽마다 해시하면 원문을 키에 넣거나 방식이 어긋날 수 있어 저장소만 이 클래스를 쓴다(M00-07).
    원문이 256비트 난수라 대입이 불가능하므로 HMAC 없이 SHA-256만으로 충분하다.
 */
public final class RefreshTokenHasher {

    private static final String HASH_ALGORITHM = "SHA-256";
    // 재사용 탐지 스크립트가 값(해시·sessionId)으로 키를 만들 때 같은 접두사를 쓰도록 공개한다(GR-35 M01-04)
    public static final String KEY_PREFIX = "auth:refresh-token:";
    public static final String SESSION_KEY_PREFIX = "auth:refresh-session:";
    private static final String USED_KEY_PREFIX = "auth:refresh-token-used:";

    private RefreshTokenHasher() {
    }

    public static String toKey(String rawToken) {
        return KEY_PREFIX + hash(rawToken);
    }

    // 교체된 토큰의 사용됨 표식 키(GR-35 M00-02). 재사용과 없는 토큰을 구분하는 데 쓴다
    public static String toUsedKey(String rawToken) {
        return USED_KEY_PREFIX + hash(rawToken);
    }

    // 세션의 현재 토큰 해시를 담는 키(GR-35 M00-02). Refresh 키 규칙을 한 곳에 두기 위해 여기에 둔다
    public static String toSessionKey(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("세션 ID는 필수입니다.");
        }
        return SESSION_KEY_PREFIX + sessionId;
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
