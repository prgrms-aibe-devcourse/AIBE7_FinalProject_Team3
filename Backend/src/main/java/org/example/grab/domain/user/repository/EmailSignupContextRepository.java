package org.example.grab.domain.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/*
    이메일 가입 컨텍스트 저장소(GR-61 M02-03). 키는 auth:email-signup-context:{토큰 SHA-256}, 값은 인증된 이메일(정규화한 값)이다.
    토큰은 256비트 난수라 대입할 수 없으므로 Refresh Token처럼 HMAC 없이 SHA-256만 쓴다(M00-01).
    값은 GR-29가 회원을 만들 때 써야 하므로 해시하지 않는다. 키에는 토큰·이메일 원문이 없다.
    호출하는 쪽은 토큰 원문만 넘기고 해시는 이 클래스 안에서만 계산한다. Redis 예외는 감싸지 않고 전파한다(GR-33 규칙).
 */
@Repository
@RequiredArgsConstructor
public class EmailSignupContextRepository {

    // MEMBER_AUTH 1.2: 발급 후 15분간 유효. 쿠키 Max-Age(900초)도 이 값을 쓴다(M04-01)
    public static final Duration CONTEXT_TTL = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "auth:email-signup-context:";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final StringRedisTemplate redisTemplate;

    // 값과 TTL을 SET 한 번으로 저장해 TTL 없는 키가 생기지 않는다
    public void save(String rawToken, String email) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("저장할 가입 컨텍스트 토큰은 필수입니다.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("가입 컨텍스트의 이메일은 필수입니다.");
        }
        redisTemplate.opsForValue().set(toKey(rawToken), email, CONTEXT_TTL);
    }

    /*
        토큰의 이메일을 조회만 하고 지우지 않는다. 요청 값 오류·DUPLICATE_NICKNAME에서는 컨텍스트를 소비하지 않아야 하기 때문이다(M00-03).
        없음·만료·이미 소비됨을 구분하지 않고 모두 비어 있다(EMAIL_SIGNUP_CONTEXT_INVALID 하나로 처리).
        토큰은 쿠키에서 오는 값이라 비어 있을 수 있으므로 예외 대신 비어 있음으로 돌려준다.
     */
    public Optional<String> findEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().get(toKey(rawToken)));
    }

    // 회원가입 성공 또는 코드 확인 이후 이메일 중복이 확인됐을 때 컨텍스트를 소비한다. 없는 토큰을 지워도 예외가 없다(멱등)
    public void delete(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        redisTemplate.delete(toKey(rawToken));
    }

    private static String toKey(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM); // SHA-256 해싱
            // 해시 알고리즘을 바이트를 입력으로 받으므로 문자열을 UTF-8 바이트 배열로 변환 후 .digest()를 통해 해시를 계산
            // 앞에 접두사 붙여 해시를 문자열로 표현하고 키 반환
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
