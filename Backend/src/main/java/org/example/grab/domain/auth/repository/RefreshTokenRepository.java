package org.example.grab.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.support.RefreshTokenHasher;
import org.example.grab.domain.auth.support.RefreshTokenProperties;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/*
    Refresh Token 저장소(GR-33 M00-06). 키는 auth:refresh-token:{원문 SHA-256}, 값은 RefreshTokenSession JSON이다.
    세션마다 auth:refresh-session:{sessionId} → 현재 토큰 해시도 함께 둔다(GR-35 M00-02).
    호출하는 쪽은 원문만 넘기고 해시는 이 클래스 안에서만 계산한다(M00-07).
    Redis 연결·타임아웃 예외는 감싸지 않고 전파한다. INVALID_TOKEN으로 바꾸면 Redis 장애 때 전원이 로그아웃된다(M03-03).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final RedisScript<Long> SAVE_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/refresh-token-save.lua"), Long.class);
    private static final RedisScript<Long> ROTATE_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/refresh-token-rotate.lua"), Long.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> REUSE_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/refresh-token-reuse.lua"), List.class);
    private static final RedisScript<Long> REVOKE_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/refresh-session-revoke.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper; // 객체 <-> JSON 변환
    private final RefreshTokenTtlCalculator ttlCalculator;
    private final RefreshTokenProperties properties;

    /*
        토큰 키(→ 세션 JSON)와 세션 키(→ 토큰 해시)를 스크립트 한 번으로 같은 TTL과 함께 저장한다(GR-35 M00-02).
        세션 키는 재사용 탐지 때 세션의 현재 토큰을 찾는 데 쓴다. 호출하는 쪽이 따로 쓰지 않도록 저장소 안에서만 쓴다.
        SET ... EX로 값과 TTL을 함께 설정하므로 TTL 없는 키가 생기지 않는다. 같은 키가 있으면 덮어쓴다(NX 없음).
        적용한 TTL(초 단위)을 돌려주므로 쿠키 Max-Age에 그대로 쓴다. 절대 만료까지 1초 미만이면 INVALID_TOKEN 예외가 발생한다.
        새 세션(로그인) 저장용이다. 재발급은 옛 토큰이 남아 있을 때만 바꿔야 하므로 rotate를 쓴다.
     */
    public Duration save(String rawToken, RefreshTokenSession session) {
        if (session == null) {
            throw new IllegalArgumentException("저장할 Refresh Token 세션은 필수입니다.");
        }

        Duration ttl = ttlCalculator.calculateTtl(session.sessionExpiresAt()); // ttl 계산
        String value = objectMapper.writeValueAsString(session); // 객체를 json 문자열로 변환
        // KEYS[1~2]: 토큰 키, 세션 키 / ARGV[1~3]: 세션 JSON, 토큰 해시, TTL(초)
        redisTemplate.execute(SAVE_SCRIPT,
                List.of(RefreshTokenHasher.toKey(rawToken), RefreshTokenHasher.toSessionKey(session.sessionId())),
                value, RefreshTokenHasher.hash(rawToken), String.valueOf(ttl.getSeconds()));
        return ttl;
    }

    public Optional<RefreshTokenSession> find(String rawToken) {
        String value = redisTemplate.opsForValue().get(RefreshTokenHasher.toKey(rawToken)); // 넘겨진 rawToken을 해싱해서 해당 키 값으로 값 조회
        if (value == null) {
            return Optional.empty();
        }

        try {
            // Redis에서 읽은 JSON 문자열을 RefreshTokenSession 객체로 변환
            return Optional.ofNullable(objectMapper.readValue(value, RefreshTokenSession.class));
        } catch (JacksonException exception) {
            // 읽을 수 없는 값은 없는 토큰과 같게 처리해 재로그인으로 회복시킨다.
            // 예외 메시지에 값 일부가 들어갈 수 있어 예외 종류만 남긴다(M03-02)
            log.warn("Refresh Token 세션 값을 읽지 못해 없는 토큰으로 처리합니다: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // 없는 토큰을 지워도 예외가 없다(멱등)
    public void delete(String rawToken) {
        redisTemplate.delete(RefreshTokenHasher.toKey(rawToken));
    }

    /*
        옛 토큰을 새 토큰으로 교체한다(GR-35 M00-01). session은 옛 토큰의 세션을 그대로 넘긴다(sessionId·sessionExpiresAt 이어받기).
        - 교체하면 적용한 TTL(초 단위)을 돌려준다. 쿠키 Max-Age에 그대로 쓴다
        - 옛 토큰 키가 이미 없으면(같은 토큰의 다른 요청이 먼저 교체했거나 세션이 폐기됨) 아무것도 바꾸지 않고 빈 결과다
        - 절대 만료까지 1초 미만이면 스크립트를 실행하기 전에 INVALID_TOKEN 예외가 발생한다(M00-05)
        교체할 때 옛 토큰의 사용됨 표식(sessionId·교체 시각)을 새 토큰과 같은 TTL로 남겨 이후 재사용을 알아본다(M00-02).
     */
    public Optional<Duration> rotate(String oldRawToken, String newRawToken, RefreshTokenSession session) {
        if (session == null) {
            throw new IllegalArgumentException("교체할 Refresh Token 세션은 필수입니다.");
        }

        Duration ttl = ttlCalculator.calculateTtl(session.sessionExpiresAt());
        String value = objectMapper.writeValueAsString(session);
        // KEYS[1~4]: 옛 토큰 키, 새 토큰 키, 옛 토큰 사용 표식 키, 세션 키 / ARGV[1~4]: 세션 JSON, 새 토큰 해시, TTL(초), sessionId
        Long rotated = redisTemplate.execute(ROTATE_SCRIPT,
                List.of(RefreshTokenHasher.toKey(oldRawToken), RefreshTokenHasher.toKey(newRawToken),
                        RefreshTokenHasher.toUsedKey(oldRawToken), RefreshTokenHasher.toSessionKey(session.sessionId())),
                value, RefreshTokenHasher.hash(newRawToken), String.valueOf(ttl.getSeconds()),
                session.sessionId().toString());
        // 교체에 성공하면 TTL을 반환, 교체하지 못하면 빈 결과를 반환
        return Long.valueOf(1).equals(rotated) ? Optional.of(ttl) : Optional.empty();
    }

    /*
        저장소에 없는 토큰이 교체된 적 있는지 확인한다(GR-35 M00-02·03). find가 빈 결과일 때 부른다.
        유예(reuse-grace)가 지난 재사용이면 같은 스크립트 안에서 그 세션의 현재 토큰과 세션 키를 지운다.
     */
    public RefreshTokenReuseCheck checkReuse(String rawToken) {
        // KEYS[1]: 사용됨 표식 키 / ARGV[1~3]: 유예(ms), 토큰 키 접두사, 세션 키 접두사
        List<?> result = redisTemplate.execute(REUSE_SCRIPT,
                List.of(RefreshTokenHasher.toUsedKey(rawToken)),
                String.valueOf(properties.reuseGrace().toMillis()),
                RefreshTokenHasher.KEY_PREFIX,
                RefreshTokenHasher.SESSION_KEY_PREFIX);

        // 루아 스크립트로 얻어진 result 배열의 첫번째 상태를 꺼내 status에 담기
        RefreshTokenReuseCheck.Status status = RefreshTokenReuseCheck.Status.valueOf(String.valueOf(result.get(0)));
        // result의 두번째 배열 값이 존재할 경우 꺼내 sessionId에 담고 없으면 null로 sessionId에 담기
        UUID sessionId = result.size() > 1 ? UUID.fromString(String.valueOf(result.get(1))) : null;
        // 최종적으로 RefreshTokenReuseCheck 객체로 반환
        return new RefreshTokenReuseCheck(status, sessionId);
    }

    // 세션의 현재 토큰 키와 세션 키를 지운다(GR-35 M00-04, 비활성 회원). 세션 키가 없어도 예외가 없다(멱등)
    public void revokeSession(UUID sessionId) {
        // KEYS[1]: 세션 키 / ARGV[1]: 토큰 키 접두사
        redisTemplate.execute(REVOKE_SCRIPT,
                List.of(RefreshTokenHasher.toSessionKey(sessionId)), RefreshTokenHasher.KEY_PREFIX);
    }
}
