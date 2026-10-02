package org.example.grab.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.support.RefreshTokenHasher;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/*
    Refresh Token 저장소(GR-33 M00-06). 키는 auth:refresh-token:{원문 SHA-256}, 값은 RefreshTokenSession JSON이다.
    호출하는 쪽은 원문만 넘기고 해시는 이 클래스 안에서만 계산한다(M00-07).
    Redis 연결·타임아웃 예외는 감싸지 않고 전파한다. INVALID_TOKEN으로 바꾸면 Redis 장애 때 전원이 로그아웃된다(M03-03).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper; // 객체 <-> JSON 변환
    private final RefreshTokenTtlCalculator ttlCalculator;

    /*
        값과 TTL을 SET 한 번으로 저장하므로 TTL 없는 키가 생기지 않는다. 같은 키가 있으면 덮어쓴다(NX 없음).
        적용한 TTL을 돌려주므로 쿠키 Max-Age에 그대로 쓴다. 절대 만료가 지났으면 INVALID_TOKEN 예외가 발생한다.
     */
    public Duration save(String rawToken, RefreshTokenSession session) {
        if (session == null) {
            throw new IllegalArgumentException("저장할 Refresh Token 세션은 필수입니다.");
        }

        Duration ttl = ttlCalculator.calculateTtl(session.sessionExpiresAt()); // ttl 계산
        String value = objectMapper.writeValueAsString(session); // 객체를 json 문자열로 변환
        redisTemplate.opsForValue().set(RefreshTokenHasher.toKey(rawToken), value, ttl); // redis에 저장
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
}
