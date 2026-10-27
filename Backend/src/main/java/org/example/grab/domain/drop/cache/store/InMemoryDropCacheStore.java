package org.example.grab.domain.drop.cache.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 인스턴스 메모리 저장소(GR-183). 단일 인스턴스 운영이라 캐시를 맞히면 네트워크 왕복이 없다.
 * 만료 항목은 읽을 때 지우고, 다시 읽히지 않는 항목은 저장할 때 SWEEP_INTERVAL마다 한 번 훑어 지운다.
 * 그래서 남는 항목은 최근 TTL + SWEEP_INTERVAL 안에 조회된 DROP 수로 한정되고, 한 번 본 DROP이 계속 쌓이지 않는다.
 * blue/green 전환 중에는 두 인스턴스의 캐시가 따로 있지만, 각자 커밋 후 이벤트로 지우고 TTL로 수렴한다.
 */
public class InMemoryDropCacheStore implements DropCacheStore {

    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(10);

    private record Entry(Object value, long expiresAtNanos) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final AtomicLong nextSweepAtNanos;

    public InMemoryDropCacheStore() {
        this(System::nanoTime);
    }

    InMemoryDropCacheStore(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.nextSweepAtNanos = new AtomicLong(nanoClock.getAsLong() + SWEEP_INTERVAL.toNanos());
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry, nanoClock.getAsLong())) {
            entries.remove(key, entry);
            return null;
        }
        return type.isInstance(entry.value()) ? type.cast(entry.value()) : null;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        long now = nanoClock.getAsLong();
        entries.put(key, new Entry(value, now + ttl.toNanos()));
        sweepIfDue(now);
    }

    @Override
    public void evict(String key) {
        entries.remove(key);
    }

    int size() {
        return entries.size();
    }

    // 동시에 저장해도 한 스레드만 훑는다. 훑는 중 새로 들어온 항목은 만료 전이라 남는다.
    private void sweepIfDue(long now) {
        long due = nextSweepAtNanos.get();
        if (now - due < 0 || !nextSweepAtNanos.compareAndSet(due, now + SWEEP_INTERVAL.toNanos())) {
            return;
        }
        entries.values().removeIf(entry -> isExpired(entry, now));
    }

    private static boolean isExpired(Entry entry, long now) {
        return now - entry.expiresAtNanos() >= 0;
    }
}
