package org.example.grab.domain.drop.cache.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.drop.cache.store.DropCacheStore;
import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.cache.store.RedisDropCacheStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DropCacheConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DropCacheConfig.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    @DisplayName("memory 저장소에만 public-drop 지표를 등록한다")
    void bindsMetricsOnlyForMemory() {
        // given / when
        for (String store : new String[]{"memory", "redis", "none"}) {
            runner.withPropertyValues("grab.drop-cache.store=" + store).run(context -> {
                // then
                assertThat(context).hasNotFailed();
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                context.getBean(MeterBinder.class).bindTo(registry);
                DropCacheStore cache = context.getBean(DropCacheStore.class);
                if (store.equals("memory")) {
                    assertThat(cache).isInstanceOf(InMemoryDropCacheStore.class);
                    cache.get("missing", String.class);
                    assertThat(registry.get("cache.gets").tag("cache", "public-drop")
                            .tag("result", "miss").functionCounter().count()).isEqualTo(1);
                } else {
                    assertThat(registry.getMeters()).isEmpty();
                    if (store.equals("redis")) {
                        assertThat(cache).isInstanceOf(RedisDropCacheStore.class);
                    } else {
                        cache.put("key", "value", Duration.ofSeconds(5));
                        assertThat(cache.get("key", String.class)).isNull();
                    }
                }
            });
        }
    }
}
