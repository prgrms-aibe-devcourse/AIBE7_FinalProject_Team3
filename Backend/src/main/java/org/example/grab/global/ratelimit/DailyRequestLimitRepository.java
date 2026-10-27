package org.example.grab.global.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;

/*
    판매자별 하루(KST) 요청 수. 키 {scope}:{sellerId}:{yyyy-MM-dd}.
    수요 분석 설명(DEMAND.md 3.1)·사진 초안(DROP.md 2.8)처럼 LLM 비용 상한용 카운터라 Redis에 두며,
    Redis를 비워도 한도가 초기화될 뿐 데이터 정합성에는 영향이 없다.
    한도와 비교해 거부하는 판단은 호출하는 서비스가 하고 이 클래스는 값을 올리고 결과만 돌려준다
    (EmailVerificationSendLimitRepository와 같은 방식).
    날짜가 키에 들어가므로 TTL은 날짜가 바뀐 뒤 키를 지우는 용도이며, 하루보다 조금 길게 둔다.
 */
@Repository
@RequiredArgsConstructor
public class DailyRequestLimitRepository {

    private static final Duration KEY_TTL = Duration.ofHours(25);

    private final StringRedisTemplate redisTemplate;

    public long increment(String scope, long sellerId, LocalDate kstDate) {
        String key = scope + ":" + sellerId + ":" + kstDate;
        Long count = redisTemplate.opsForValue().increment(key);
        // null은 파이프라인·트랜잭션 안에서만 온다. 0으로 바꾸면 한도 아래로 보여 요청이 통과하므로 예외로 막는다
        if (count == null) {
            throw new IllegalStateException("Redis INCR 결과가 없습니다.");
        }
        if (count == 1L) {
            redisTemplate.expire(key, KEY_TTL);
        }
        return count;
    }
}
