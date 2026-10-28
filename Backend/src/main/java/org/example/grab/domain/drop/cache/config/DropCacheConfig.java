package org.example.grab.domain.drop.cache.config;

import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.cache.store.DropCacheStore;
import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.cache.store.RedisDropCacheStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(DropCacheProperties.class)
public class DropCacheConfig {

    @Bean
    public DropCacheStore dropCacheStore(DropCacheProperties properties, StringRedisTemplate redisTemplate,
                                         ObjectMapper objectMapper) {
        return switch (properties.store()) {
            case MEMORY -> new InMemoryDropCacheStore();
            case REDIS -> new RedisDropCacheStore(redisTemplate, objectMapper);
            // 캐시를 쓰지 않는다. 항상 없음으로 응답해 매번 DB에서 조회한다.
            case NONE -> new DropCacheStore() {
                @Override
                public <T> T get(String key, Class<T> type) {
                    return null;
                }

                @Override
                public void put(String key, Object value, Duration ttl) {
                }

                @Override
                public void evict(String key) {
                }
            };
        };
    }

    @Bean
    public PublicDropCache publicDropCache(DropCacheStore dropCacheStore) {
        return new PublicDropCache(dropCacheStore);
    }
}
