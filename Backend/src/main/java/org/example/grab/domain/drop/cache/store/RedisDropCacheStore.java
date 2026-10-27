package org.example.grab.domain.drop.cache.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Redis 저장소(GR-183). 응답을 JSON 문자열로 저장한다. blue/green 전환 중에도 두 인스턴스가 같은 캐시를 본다.
 * 캐시를 맞혀도 Redis 왕복 한 번과 직렬화 비용이 남는다. Redis는 화면 조회 응답 캐시로만 쓰며 재고 판단에 쓰지 않는다.
 * Redis 장애는 조회 실패로 만들지 않는다(DB에서 다시 만들 수 있다).
 */
@Slf4j
public class RedisDropCacheStore implements DropCacheStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisDropCacheStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            log.warn("공개 DROP 캐시 읽기 실패, DB에서 조회: {}", exception.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException exception) {
            log.warn("공개 DROP 캐시 쓰기 실패: {}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("공개 DROP 캐시 삭제 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
