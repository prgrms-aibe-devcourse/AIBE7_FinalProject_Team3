package org.example.grab.domain.drop.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인스턴스 메모리 저장소(GR-183). 단일 인스턴스 운영이라 캐시를 맞히면 네트워크 왕복이 없다.
 * 키는 공개 DROP당 상세·재고 두 개라 개수가 DROP 수로 한정된다. 만료 항목은 읽을 때 지우고 같은 키를 다시 쓰면 덮인다.
 * blue/green 전환 중에는 두 인스턴스의 캐시가 따로 있지만, 각자 커밋 후 이벤트로 지우고 TTL로 수렴한다.
 */
public class InMemoryDropCacheStore implements DropCacheStore {

    private record Entry(Object value, long expiresAtNanos) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public <T> T get(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            entries.remove(key, entry);
            return null;
        }
        return type.isInstance(entry.value()) ? type.cast(entry.value()) : null;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        entries.put(key, new Entry(value, System.nanoTime() + ttl.toNanos()));
    }

    @Override
    public void evict(String key) {
        entries.remove(key);
    }
}
