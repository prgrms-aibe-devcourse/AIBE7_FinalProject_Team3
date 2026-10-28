package org.example.grab.domain.drop.cache.service;

import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDropCacheTest {

    private static final Duration TTL = Duration.ofMinutes(1);
    private static final long DROP_ID = 7L;
    private static final String KEY = PublicDropCache.detailKey(DROP_ID);

    private PublicDropCache cache;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cache = new PublicDropCache(new InMemoryDropCacheStore());
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("캐시를 맞히면 조회 함수를 다시 부르지 않는다")
    void returnsCachedValue() {
        // given
        AtomicInteger loads = new AtomicInteger();

        // when
        String first = cache.get(KEY, DROP_ID, String.class, TTL, () -> "v" + loads.incrementAndGet());
        String second = cache.get(KEY, DROP_ID, String.class, TTL, () -> "v" + loads.incrementAndGet());

        // then
        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("변경 이벤트로 지우면 다음 조회는 DB에서 다시 읽는다")
    void reloadsAfterEvict() {
        // given
        AtomicInteger loads = new AtomicInteger();
        cache.get(KEY, DROP_ID, String.class, TTL, () -> "v" + loads.incrementAndGet());

        // when
        cache.evict(DROP_ID);
        String after = cache.get(KEY, DROP_ID, String.class, TTL, () -> "v" + loads.incrementAndGet());

        // then
        assertThat(after).isEqualTo("v2");
    }

    @Test
    @DisplayName("같은 키를 동시에 조회하면 조회 함수는 한 번만 실행되고 모두 같은 값을 받는다")
    void loadsOnceForConcurrentMisses() throws Exception {
        // given: 첫 조회가 끝나지 않은 동안 나머지 요청이 들어온다
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        Queue<Thread> workers = new ConcurrentLinkedQueue<>();

        // when
        for (int i = 0; i < 8; i++) {
            results.add(executor.submit(() -> {
                workers.add(Thread.currentThread());
                return cache.get(KEY, DROP_ID, String.class, TTL, () -> {
                    loads.incrementAndGet();
                    await(release);
                    return "v";
                });
            }));
        }
        awaitAllWaiting(workers, 8);
        release.countDown();

        // then
        for (Future<String> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("v");
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("지우기 전에 시작한 조회가 지우기 뒤에 끝나면 옛 값을 캐시에 넣지 않는다")
    void doesNotStoreValueLoadedBeforeEvict() throws Exception {
        // given: 커밋 전 값을 읽은 조회가 아직 끝나지 않았다
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> stale = executor.submit(() -> cache.get(KEY, DROP_ID, String.class, TTL, () -> {
            loading.countDown();
            await(release);
            return "old";
        }));
        loading.await(5, TimeUnit.SECONDS);

        // when: 커밋 후 이벤트로 지운 뒤 옛 조회가 끝난다
        cache.evict(DROP_ID);
        release.countDown();

        // then: 옛 조회를 시작한 요청은 옛 값을 받지만 캐시에는 남지 않는다
        assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo("old");
        assertThat(cache.get(KEY, DROP_ID, String.class, TTL, () -> "new")).isEqualTo("new");
    }

    @Test
    @DisplayName("지운 뒤 들어온 요청은 지우기 전에 시작한 조회를 기다리지 않고 새로 읽는다")
    void doesNotJoinLoadStartedBeforeEvict() throws Exception {
        // given
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> stale = executor.submit(() -> cache.get(KEY, DROP_ID, String.class, TTL, () -> {
            loading.countDown();
            await(release);
            return "old";
        }));
        loading.await(5, TimeUnit.SECONDS);
        cache.evict(DROP_ID);

        // when: SSE 알림을 받은 화면의 재조회가 옛 조회가 끝나기 전에 들어온다
        String fresh = cache.get(KEY, DROP_ID, String.class, TTL, () -> "new");
        release.countDown();

        // then
        assertThat(fresh).isEqualTo("new");
        assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo("old");
        assertThat(cache.get(KEY, DROP_ID, String.class, TTL, () -> "other")).isEqualTo("new");
    }

    @Test
    @DisplayName("조회 실패는 캐시하지 않고 기다리던 요청에도 같은 예외를 전달한다")
    void doesNotCacheFailure() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        List<Future<String>> results = new ArrayList<>();
        Queue<Thread> workers = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> {
                workers.add(Thread.currentThread());
                return cache.get(KEY, DROP_ID, String.class, TTL, () -> {
                    loads.incrementAndGet();
                    await(release);
                    throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
                });
            }));
        }
        awaitAllWaiting(workers, 3);

        // when
        release.countDown();

        // then
        for (Future<String> result : results) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
        }
        assertThat(loads).hasValue(1);
        assertThat(cache.get(KEY, DROP_ID, String.class, TTL, () -> "found")).isEqualTo("found");
    }

    @Test
    @DisplayName("조회 함수가 Error를 던져도 기다리던 요청이 영구 대기하지 않고 같은 Error를 받는다")
    void propagatesErrorToJoinedRequests() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        List<Future<String>> results = new ArrayList<>();
        Queue<Thread> workers = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> {
                workers.add(Thread.currentThread());
                return cache.get(KEY, DROP_ID, String.class, TTL, () -> {
                    loads.incrementAndGet();
                    await(release);
                    throw new AssertionError("loader failed");
                });
            }));
        }
        awaitAllWaiting(workers, 3);

        // when
        release.countDown();

        // then
        for (Future<String> result : results) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AssertionError.class);
        }
        assertThat(loads).hasValue(1);
        assertThat(cache.get(KEY, DROP_ID, String.class, TTL, () -> "found")).isEqualTo("found");
    }

    @Test
    @DisplayName("TTL이 지나면 다시 읽는다")
    void expiresAfterTtl() throws Exception {
        // given
        AtomicInteger loads = new AtomicInteger();
        cache.get(KEY, DROP_ID, String.class, Duration.ofMillis(50), () -> "v" + loads.incrementAndGet());

        // when
        Thread.sleep(100);
        String after = cache.get(KEY, DROP_ID, String.class, TTL, () -> "v" + loads.incrementAndGet());

        // then
        assertThat(after).isEqualTo("v2");
    }

    /*
     * 작업자 전원이 대기 상태가 될 때까지 기다린다. 조회를 맡은 작업자는 release 래치(시간 제한 대기, TIMED_WAITING)에서,
     * 나머지는 진행 중인 조회의 join()(WAITING)에서 대기하므로 전원이 대기 중이면 모두 합류한 것이다.
     * 고정 sleep은 늦게 시작한 작업자가 합류했는지 보장하지 못한다.
     */
    private static void awaitAllWaiting(Queue<Thread> workers, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workers.size() < count || !workers.stream().allMatch(PublicDropCacheTest::isWaiting)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("작업자가 모두 대기 상태가 되지 않았다");
            }
            Thread.sleep(5);
        }
    }

    private static boolean isWaiting(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
