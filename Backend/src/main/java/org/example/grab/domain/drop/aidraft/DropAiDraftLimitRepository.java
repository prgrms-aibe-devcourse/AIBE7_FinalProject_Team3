package org.example.grab.domain.drop.aidraft;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;

/*
    판매자별 하루(KST) 사진 초안 요청 수(DROP.md 2.8). 비용 상한용 카운터라 Redis에 두며,
    Redis를 비워도 한도가 초기화될 뿐 데이터 정합성에는 영향이 없다.
    INCR은 원자적이라 동시 요청도 서로 다른 값을 받는다. 날짜가 키에 들어가므로 TTL은 하루를 조금 넘게 걸어 정리만 맡긴다.
 */
@Repository
@RequiredArgsConstructor
public class DropAiDraftLimitRepository {

    private static final String KEY_PREFIX = "drop:ai-draft:";
    private static final Duration KEY_TTL = Duration.ofHours(25);

    private final StringRedisTemplate redisTemplate;

    public long increment(long sellerId, LocalDate kstDate) {
        String key = KEY_PREFIX + sellerId + ":" + kstDate;
        Long count = redisTemplate.opsForValue().increment(key);
        // null은 파이프라인·트랜잭션 안에서만 온다. 0으로 보면 한도 아래로 보여 통과하므로 예외로 막는다
        if (count == null) {
            throw new IllegalStateException("Redis INCR 결과가 없습니다.");
        }
        if (count == 1L) {
            redisTemplate.expire(key, KEY_TTL);
        }
        return count;
    }
}
