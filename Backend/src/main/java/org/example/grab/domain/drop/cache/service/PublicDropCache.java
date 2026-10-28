package org.example.grab.domain.drop.cache.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.example.grab.domain.drop.cache.store.DropCacheStore;

/**
 * 공개 DROP 상세·재고 응답 캐시(GR-183). SSE 알림을 받은 화면들이 같은 상품을 한꺼번에 다시 조회해도 DB 조회는 한 번만 일어나게 한다.
 *
 * 정합성 규칙:
 * - 지우기: 재고·판매·상태 변경이 커밋된 뒤(AFTER_COMMIT) 해당 DROP의 세대를 올리고 키를 지운다. 지운 뒤 시작한 조회는 커밋된 값을 읽는다.
 * - 옛 값 재저장 막기: 커밋 전에 시작한 조회가 지우기 뒤에 끝나면 옛 값을 다시 넣을 수 있다. 조회 시작 시 세대를 기억해 두고,
 *   끝났을 때 세대가 바뀌었으면 저장하지 않는다. 확인과 저장 사이에 지우기가 끼면 저장 뒤 세대를 다시 보고 지운다.
 * - 몰림 막기: 같은 키를 동시에 조회하면 먼저 시작한 조회 하나를 함께 기다린다. 다만 그 조회가 지우기 전에 시작한 것이면
 *   기다리지 않고 새로 조회한다(지우기 뒤의 요청이 옛 값을 받지 않게 한다).
 * - 실패(DROP_NOT_FOUND 등)는 저장하지 않는다.
 *
 * 세대는 인스턴스 메모리에 둔다. 단일 인스턴스 운영 전제이며, Redis 저장소에서도 다른 인스턴스의 지우기는 세대에 반영되지 않는다.
 * 세대 맵의 크기는 변경된 DROP 수로 한정된다.
 */
public class PublicDropCache {

    private record Flight(long generation, CompletableFuture<Object> result) {
    }

    private final DropCacheStore store;
    private final Map<Long, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Map<String, Flight> inFlight = new ConcurrentHashMap<>();

    public PublicDropCache(DropCacheStore store) {
        this.store = store;
    }

    public static String detailKey(long dropId) {
        return "grab:drop:public:detail:" + dropId;
    }

    public static String stockKey(long dropId) {
        return "grab:drop:public:stock:" + dropId;
    }

    public <T> T get(String key, long dropId, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = store.get(key, type);
        if (cached != null) {
            return cached;
        }
        long generation = generation(dropId);
        Flight mine = new Flight(generation, new CompletableFuture<>());
        Flight running = inFlight.compute(key, (k, current) ->
                current != null && current.generation() == generation ? current : mine);
        if (running != mine) {
            return type.cast(join(running.result()));
        }
        try {
            T value = loader.get();
            if (generation(dropId) == generation) {
                store.put(key, value, ttl);
                if (generation(dropId) != generation) {
                    store.evict(key);
                }
            }
            mine.result().complete(value);
            return value;
        } catch (RuntimeException | Error exception) {
            // Error도 future를 끝내야 합류해 기다리던 요청이 영구 대기하지 않는다
            mine.result().completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** 커밋 후 변경 이벤트에서 부른다. 상세·재고를 함께 지운다. */
    public void evict(long dropId) {
        generations.computeIfAbsent(dropId, id -> new AtomicLong()).incrementAndGet();
        store.evict(detailKey(dropId));
        store.evict(stockKey(dropId));
    }

    private long generation(long dropId) {
        AtomicLong generation = generations.get(dropId);
        return generation == null ? 0 : generation.get();
    }

    private static Object join(CompletableFuture<Object> result) {
        try {
            return result.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
