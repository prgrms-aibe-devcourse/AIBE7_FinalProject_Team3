package org.example.grab.domain.drop.cache.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import java.time.Duration;

/**
 * 공개 DROP 응답의 Caffeine 저장소(GR-213). 항목별 쓰기 TTL과 최대 항목 수로 제한한다.
 * 상세·재고·관련 추천이 함께 사용하며, 읽기로 TTL을 연장하지 않는다.
 * 인스턴스 간 무효화는 공유하지 않으므로 다른 인스턴스의 변경은 TTL로 수렴한다.
 */
public class InMemoryDropCacheStore implements DropCacheStore {

    public static final long DEFAULT_MAX_SIZE = 1_000;
    public static final String CACHE_NAME = "public-drop";

    private record Entry(Object value, long ttlNanos) {
    }

    private final Cache<String, Entry> entries;

    public InMemoryDropCacheStore() {
        this(DEFAULT_MAX_SIZE);
    }

    public InMemoryDropCacheStore(long maxSize) {
        this(maxSize, Ticker.systemTicker());
    }

    InMemoryDropCacheStore(long maxSize, Ticker ticker) {
        entries = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .ticker(ticker)
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Entry entry = entries.getIfPresent(key);
        return entry != null && type.isInstance(entry.value()) ? type.cast(entry.value()) : null;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        entries.put(key, new Entry(value, ttl.toNanos()));
    }

    @Override
    public void evict(String key) {
        entries.invalidate(key);
    }

    public void bindMetrics(MeterRegistry registry) {
        new CaffeineCacheMetrics<>(entries, CACHE_NAME, Tags.empty()).bindTo(registry);
    }

    // Caffeine의 비동기 유지보수가 끝난 크기. 운영 요청 경로에서는 호출하지 않는다.
    long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }
}
