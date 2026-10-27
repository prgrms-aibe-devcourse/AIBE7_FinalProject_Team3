package org.example.grab.domain.drop.cache.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDropCacheStoreTest {

    private static final Duration TTL = Duration.ofSeconds(5);

    @Test
    @DisplayName("TTL이 지난 항목은 읽으면 없다")
    void expiresOnRead() {
        // given
        AtomicLong now = new AtomicLong();
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(now::get);
        store.put("a", "value", TTL);

        // when
        now.addAndGet(TTL.toNanos());

        // then
        assertThat(store.get("a", String.class)).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("다시 읽히지 않는 만료 항목은 정리 주기가 지난 뒤의 저장에서 지운다")
    void sweepsExpiredEntriesOnPut() {
        // given: 많은 DROP을 한 번씩만 조회한 상태
        AtomicLong now = new AtomicLong();
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(now::get);
        for (int i = 0; i < 100; i++) {
            store.put("old-" + i, "value", TTL);
        }

        // when: 정리 주기가 지난 뒤 다른 키를 저장한다
        now.addAndGet(InMemoryDropCacheStore.SWEEP_INTERVAL.toNanos());
        store.put("new", "value", TTL);

        // then: 만료 항목은 지워지고 방금 저장한 항목만 남는다
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get("new", String.class)).isEqualTo("value");
    }

    @Test
    @DisplayName("정리 주기 전에는 만료 항목을 훑지 않고, 만료 전 항목은 정리해도 남는다")
    void keepsLiveEntriesAndWaitsForInterval() {
        // given
        AtomicLong now = new AtomicLong();
        InMemoryDropCacheStore store = new InMemoryDropCacheStore(now::get);
        store.put("old", "value", TTL);

        // when: TTL은 지났지만 정리 주기 전
        now.addAndGet(TTL.toNanos());
        store.put("live", "value", Duration.ofMinutes(1));

        // then
        assertThat(store.size()).isEqualTo(2);

        // when: 정리 주기가 지남
        now.set(InMemoryDropCacheStore.SWEEP_INTERVAL.toNanos());
        store.put("another", "value", TTL);

        // then
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("live", String.class)).isEqualTo("value");
    }
}
