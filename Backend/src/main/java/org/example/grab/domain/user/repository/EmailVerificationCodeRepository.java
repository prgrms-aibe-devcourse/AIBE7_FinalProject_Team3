package org.example.grab.domain.user.repository;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.support.EmailVerificationHasher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/*
    이메일 인증 코드 저장소(GR-61 M02-01). 이메일 하나에 키 두 개를 둔다.
    - auth:email-verification-code:{이메일 HMAC} → 코드 HMAC
    - auth:email-verification-attempts:{이메일 HMAC} → 확인 시도 횟수
    시도 횟수를 코드 값에 함께 두면 읽고-고쳐-쓰는 사이에 동시 요청이 끼어 횟수가 덜 올라간다.
    INCR은 원자적이라 동시에 틀린 코드를 보내도 요청마다 다른 횟수를 받는다.
    호출하는 쪽은 이메일·코드 원문만 넘기고, 키·값의 해시는 이 클래스와 EmailVerificationHasher 안에서만 만든다.
    Redis 예외는 감싸지 않고 전파한다(GR-33 규칙).
 */
@Repository
@RequiredArgsConstructor
public class EmailVerificationCodeRepository {

    // MEMBER_AUTH 1.2: 발송 후 5분간 유효. 메일 본문의 유효 시간 안내도 이 값을 쓴다(M03-02)
    public static final Duration CODE_TTL = Duration.ofMinutes(5);

    private static final String CODE_KEY_PREFIX = "auth:email-verification-code:";
    private static final String ATTEMPTS_KEY_PREFIX = "auth:email-verification-attempts:";

    private final StringRedisTemplate redisTemplate;
    private final EmailVerificationHasher hasher;

    /*
        같은 이메일의 이전 코드를 덮어써 폐기하고, 새 코드의 시도 횟수를 0부터 다시 센다.
        코드는 값과 TTL을 SET 한 번으로 저장해 TTL 없는 키가 생기지 않는다.
        시도 횟수 삭제가 실패하면 예외가 전파되어 메일을 보내지 않으며, 새 코드가 이전 횟수를 이어받는 쪽(더 엄격한 쪽)으로 남는다.
     */
    public void save(String email, String code) {
        String emailHash = hasher.hashEmail(email);
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + emailHash, hasher.hashVerificationCode(code), CODE_TTL);
        redisTemplate.delete(ATTEMPTS_KEY_PREFIX + emailHash);
    }

    // 저장된 코드 HMAC. 비교는 EmailVerificationHasher.matchesVerificationCode로 한다
    public Optional<String> findCodeHash(String email) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(CODE_KEY_PREFIX + hasher.hashEmail(email)));
    }

    /*
        시도 횟수를 1 올리고 올린 값을 돌려준다. 고정 구간 카운터처럼 첫 증가 때만 TTL을 건다.
        코드보다 늦게 생기므로 코드가 만료된 뒤에도 잠시 남을 수 있지만, 새 코드를 저장할 때 지워진다.
     */
    public long incrementAttempts(String email) {
        String key = ATTEMPTS_KEY_PREFIX + hasher.hashEmail(email);
        Long attempts = redisTemplate.opsForValue().increment(key);
        // null은 파이프라인·트랜잭션 안에서만 온다. 0으로 바꾸면 5회 제한 아래로 보여 코드 비교가 허용되므로 예외로 막는다
        if (attempts == null) {
            throw new IllegalStateException("Redis INCR 결과가 없습니다.");
        }
        if (attempts == 1L) {
            redisTemplate.expire(key, CODE_TTL);
        }
        return attempts;
    }

    /*
        코드와 시도 횟수를 지운다. 코드 키를 이 호출이 지웠으면 true다.
        같은 코드로 확인 요청이 동시에 와도 true는 한 요청만 받으므로, 성공 처리를 true일 때만 하면 코드가 한 번만 쓰인다.
     */
    public boolean delete(String email) {
        String emailHash = hasher.hashEmail(email);
        Boolean deleted = redisTemplate.delete(CODE_KEY_PREFIX + emailHash);
        redisTemplate.delete(ATTEMPTS_KEY_PREFIX + emailHash);
        return Boolean.TRUE.equals(deleted);
    }
}
