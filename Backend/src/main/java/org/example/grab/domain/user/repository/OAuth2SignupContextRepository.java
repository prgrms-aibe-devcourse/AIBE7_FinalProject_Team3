package org.example.grab.domain.user.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/*
    소셜 가입 컨텍스트 저장소(GR-46 M00-06). 키는 auth:oauth2-signup-context:{토큰 SHA-256}, 값은 OAuth2SignupContext JSON이다.
    LOCAL 가입 컨텍스트(EmailSignupContextRepository)와 키·쿠키를 나눠 서로의 토큰으로 가입할 수 없게 한다.
    토큰은 256비트 난수라 HMAC 없이 SHA-256만 쓴다. 키에는 토큰·이메일·식별자 원문이 없다(GR-33 인계).
    조회와 삭제를 나눈다. 닉네임·요청 값 오류에서는 컨텍스트를 소비하지 않아야 하기 때문이다.
    Redis 예외는 감싸지 않고 전파한다(GR-33 규칙).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OAuth2SignupContextRepository {

    // MEMBER_AUTH 1.5: 발급 후 15분간 유효. 쿠키 Max-Age(900초)도 이 값을 쓴다
    public static final Duration CONTEXT_TTL = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "auth:oauth2-signup-context:";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 값과 TTL을 SET 한 번으로 저장해 TTL 없는 키가 생기지 않는다
    public void save(String rawToken, OAuth2SignupContext context) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("저장할 소셜 가입 컨텍스트 토큰은 필수입니다.");
        }
        if (context == null) {
            throw new IllegalArgumentException("저장할 소셜 가입 컨텍스트는 필수입니다.");
        }
        redisTemplate.opsForValue().set(toKey(rawToken), objectMapper.writeValueAsString(context), CONTEXT_TTL);
    }

    /*
        조회만 하고 지우지 않는다. 없음·만료·이미 소비됨·읽을 수 없는 값을 구분하지 않고 모두 비어 있다(OAUTH2_SIGNUP_CONTEXT_INVALID).
        토큰은 쿠키에서 오는 값이라 비어 있을 수 있으므로 예외 대신 비어 있음으로 돌려준다.
     */
    public Optional<OAuth2SignupContext> find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(toKey(rawToken));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, OAuth2SignupContext.class));
        } catch (JacksonException exception) {
            // 필수 필드 누락(생성자 검증 실패)도 JacksonException으로 감싸져 온다. 예외 메시지에 값 일부가 들어갈 수 있어 종류만 남긴다
            log.warn("소셜 가입 컨텍스트 값을 읽지 못해 없는 컨텍스트로 처리합니다: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // 가입 성공 또는 DUPLICATE_EMAIL에서 소비한다. 없는 토큰을 지워도 예외가 없다(멱등)
    public void delete(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        redisTemplate.delete(toKey(rawToken));
    }

    private static String toKey(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
