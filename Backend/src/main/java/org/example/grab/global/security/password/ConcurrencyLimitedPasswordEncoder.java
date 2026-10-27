package org.example.grab.global.security.password;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/*
    비밀번호 해시(Argon2id)를 동시에 정해진 수까지만 실행한다(GR-178, NFR-014, MEMBER_AUTH 1.2.3).
    해시 1건이 진행되는 동안 힙 약 19MiB를 잡아, 동시에 몰리면 힙이 포화되고 Tomcat 스레드까지 OOM으로 죽어 서버가 응답하지 않게 된다(GR-161).
    로그인·더미 해시·회원가입·비밀번호 변경이 모두 이 인코더를 거치도록 SecurityConfig에서 감싼다(M00-04).
    - 자리가 없으면 acquireTimeout까지 먼저 온 순서대로 기다리고, 그 안에 차례가 오지 않으면 503으로 거부한다(M00-01)
    - 자리는 호출마다 잡고 놓는다. 해시를 두 번 하는 비밀번호 변경도 두 해시 사이에 자리를 잡고 있지 않는다
    - 호출하는 서비스는 트랜잭션 없이 DB 연결을 돌려준 뒤 해시하므로, 기다리는 동안 DB 연결은 잡지 않는다
 */
public class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    // 공정 모드: 시간 제한이 있는 tryAcquire도 먼저 기다린 요청부터 자리를 준다
    // Semaphore: 특정 코드 구간에 동시에 진입해서 실행할 수 있는 스레드 수를 제한하는 객체
    private final Semaphore permits;
    private final long acquireTimeoutNanos;
    private final long retryAfterSeconds;
    // 거부 수는 지표(PasswordHashMetricsConfig)로 노출한다. 여러 요청 스레드가 함께 올리므로 원자적으로 센다
    private final AtomicLong rejected = new AtomicLong();

    public ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, PasswordHashProperties properties) {
        this.delegate = delegate;
        this.permits = new Semaphore(properties.maxConcurrent(), true);
        this.acquireTimeoutNanos = properties.acquireTimeout().toNanos();
        this.retryAfterSeconds = properties.retryAfter().getSeconds();
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withPermit(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withPermit(() -> delegate.matches(rawPassword, encodedPassword));
    }

    // 저장된 해시에 적힌 파라미터만 비교하고 해시를 계산하지 않으므로 자리를 잡지 않는다
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    // 입장하지 못한 요청이 몇 건인가
    public long rejectedCount() {
        return rejected.get();
    }

    // 지금 기다리는 요청이 몇 개인가
    // Semaphore.getQueueLength()는 Javadoc상 추정값이다. 정확한 개수가 아니라 대기 추이를 보는 용도로만 쓴다
    public int waitingCount() {
        return permits.getQueueLength();
    }

    private <T> T withPermit(Supplier<T> hash) {
        acquire();
        try {
            return hash.get();
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // 요청 스레드를 멈추려는 신호라 해시를 하지 않는다. 인터럽트 상태를 되살려 호출한 쪽이 알 수 있게 하고, 응답은 거부와 같게 한다
            Thread.currentThread().interrupt();
            throw rejected();
        }
        if (!acquired) {
            throw rejected();
        }
    }

    private BusinessException rejected() {
        rejected.incrementAndGet();
        return new BusinessException(CommonErrorCode.AUTH_TEMPORARILY_UNAVAILABLE, retryAfterSeconds);
    }
}
