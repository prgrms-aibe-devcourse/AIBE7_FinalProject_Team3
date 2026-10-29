package org.example.grab.domain.drop.cache.store;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDropCacheStoreTest {

    @Test
    @DisplayName("상세·재고·추천의 TTL은 읽어도 연장되지 않고 경계에서 만료된다")
    void expiresIndependentlyWithoutExtendingOnRead() {
        // given
        AtomicLong now = new AtomicLong();
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(1000, now::get);
        store.put("detail", "detail", Duration.ofSeconds(5));
        store.put("stock", "stock", Duration.ofSeconds(2));
        store.put("related", "related", Duration.ofMinutes(3));

        // when
        now.set(Duration.ofSeconds(1).toNanos());
        assertThat(store.get("stock", String.class)).isEqualTo("stock");
        now.set(Duration.ofSeconds(2).toNanos());

        // then
        assertThat(store.get("stock", String.class)).isNull();
        assertThat(store.get("detail", String.class)).isEqualTo("detail");
        now.set(Duration.ofSeconds(5).toNanos());
        assertThat(store.get("detail", String.class)).isNull();
        assertThat(store.get("related", String.class)).isEqualTo("related");
        now.set(Duration.ofMinutes(3).toNanos());
        assertThat(store.get("related", String.class)).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("같은 키를 덮어쓰면 새 TTL을 저장 시각부터 적용한다")
    void resetsExpirationOnUpdate() {
        // given
        AtomicLong now = new AtomicLong();
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(1000, now::get);
        store.put("key", "old", Duration.ofSeconds(5));

        // when
        now.set(Duration.ofSeconds(4).toNanos());
        store.put("key", "new", Duration.ofSeconds(2));

        // then
        now.set(Duration.ofSeconds(5).toNanos());
        assertThat(store.get("key", String.class)).isEqualTo("new");
        now.set(Duration.ofSeconds(6).toNanos());
        assertThat(store.get("key", String.class)).isNull();
    }

    @Test
    @DisplayName("다수 키 저장 후 유지보수가 끝나면 최대 항목 수를 지키고 축출 지표를 노출한다")
    void boundsSizeAndRecordsMetrics() {
        // given
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        store.bindMetrics(registry);
        store.put("hit", "value", Duration.ofMinutes(3));
        store.get("hit", String.class);
        store.get("missing", String.class);

        // when
        for (int i = 0; i < 100; i++) {
            store.put("key-" + i, "value", Duration.ofMinutes(3));
        }

        // then
        assertThat(store.size()).isLessThanOrEqualTo(10);
        assertThat(registry.get("cache.gets").tag("cache", "public-drop").tag("result", "hit")
                .functionCounter().count()).isEqualTo(1);
        assertThat(registry.get("cache.gets").tag("cache", "public-drop").tag("result", "miss")
                .functionCounter().count()).isEqualTo(1);
        assertThat(registry.get("cache.evictions").tag("cache", "public-drop")
                .functionCounter().count()).isPositive();
        assertThat(registry.get("cache.size").tag("cache", "public-drop").gauge().value())
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("명시적으로 지운 값과 다른 타입으로 요청한 값은 반환하지 않는다")
    void invalidatesAndChecksType() {
        // given
        InMemoryDropCacheStore store = new InMemoryDropCacheStore();
        store.put("key", "value", Duration.ofSeconds(5));

        // when
        assertThat(store.get("key", Long.class)).isNull();
        store.evict("key");

        // then
        assertThat(store.get("key", String.class)).isNull();
    }
}
