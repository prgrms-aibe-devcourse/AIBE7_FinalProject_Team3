package org.example.grab.domain.drop.cache.store;

import java.time.Duration;

/**
 * 공개 DROP 응답 캐시의 저장소(GR-183). 메모리·Redis 구현을 설정으로 바꿔 끼운다.
 * 저장소 장애는 조회 실패로 이어지지 않아야 한다. 읽기 실패는 캐시 없음, 쓰기·삭제 실패는 무시로 처리한다.
 */
public interface DropCacheStore {

    /** 없거나 만료됐거나 읽을 수 없으면 null이다. */
    <T> T get(String key, Class<T> type);

    void put(String key, Object value, Duration ttl);

    void evict(String key);
}
