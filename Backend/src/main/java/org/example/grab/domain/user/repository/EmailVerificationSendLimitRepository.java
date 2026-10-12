package org.example.grab.domain.user.repository;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.support.EmailVerificationHasher;
import org.example.grab.domain.user.support.EmailVerificationSendLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/*
    이메일 인증 코드 발송 제한 저장소(GR-61 M02-02). 키 세 개를 둔다.
    - auth:email-verification-resend:{이메일 HMAC} → 재발송 간격 동안만 존재하는 표시
    - auth:email-verification-email-requests:{이메일 HMAC} → 이메일별 요청 횟수(고정 구간)
    - auth:email-verification-ip-requests:{IP HMAC} → IP별 요청 횟수(고정 구간)
    한도와 비교해 거부하는 판단은 서비스가 한다(Repository에 비즈니스 분기를 두지 않음). 이 클래스는 값을 올리고 결과만 돌려준다.
    Redis 예외는 감싸지 않고 전파한다(GR-33 규칙).
 */
@Repository
@RequiredArgsConstructor
public class EmailVerificationSendLimitRepository {

    private static final String RESEND_KEY_PREFIX = "auth:email-verification-resend:";
    private static final String EMAIL_REQUESTS_KEY_PREFIX = "auth:email-verification-email-requests:";
    private static final String IP_REQUESTS_KEY_PREFIX = "auth:email-verification-ip-requests:";

    private final StringRedisTemplate redisTemplate;
    private final EmailVerificationHasher hasher;
    private final EmailVerificationSendLimitProperties properties;

    /*
        재발송 간격 1분 TTL을 시작하고 시작했으면 true, 간격 안이면 false를 돌려준다.
        SET NX EX 한 명령이라 같은 이메일로 동시에 요청해도 true는 한 요청만 받는다.
        키가 없을 때만 저장, true
     */
    public boolean tryStartResendInterval(String email) {
        Boolean started = redisTemplate.opsForValue()
                .setIfAbsent(RESEND_KEY_PREFIX + hasher.hashEmail(email), "1", properties.resendInterval());
        return Boolean.TRUE.equals(started);
    }

    // 이메일별 요청 횟수를 1 올리고 올린 값을 돌려준다
    public long incrementEmailRequests(String email) {
        return increment(EMAIL_REQUESTS_KEY_PREFIX + hasher.hashEmail(email), properties.emailWindow());
    }

    // IP별 요청 횟수를 1 올리고 올린 값을 돌려준다. IP는 request.getRemoteAddr() 값을 받는다(M00-04)
    public long incrementIpRequests(String ipAddress) {
        return increment(IP_REQUESTS_KEY_PREFIX + hasher.hashIpAddress(ipAddress), properties.ipWindow());
    }

    /*
        고정 구간 카운터: INCR은 원자적이라 동시 요청도 서로 다른 값을 받고, 첫 증가 때만 TTL을 걸어 구간이 첫 요청부터 시작한다.
        한도를 넘은 요청도 횟수를 올리지만 TTL은 다시 걸지 않으므로 계속 요청해도 구간이 늘어나지 않는다.
     */
    private long increment(String key, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        // null은 파이프라인·트랜잭션 안에서만 온다. 0으로 바꾸면 한도 아래로 보여 요청이 통과하므로 예외로 막는다
        if (count == null) {
            throw new IllegalStateException("Redis INCR 결과가 없습니다.");
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        return count;
    }
}
