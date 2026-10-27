package org.example.grab.global.security.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/*
    비밀번호 해시(Argon2id) 동시 실행 상한 설정(GR-178 M00-02·M00-03, MEMBER_AUTH 1.2.3, NFR-014).
    해시 1건이 진행되는 동안 힙 약 19MiB를 잡으므로, 동시에 몰리면 힙이 포화되어 OOM과 응답 불가가 생긴다(GR-161).
    처리량은 CPU 수가 정하므로 상한을 작게 두어도 처리량은 줄지 않고 힙 사용만 묶인다.
    - maxConcurrent: 동시에 실행하는 해시 수
    - acquireTimeout: 자리가 없을 때 차례를 기다리는 최대 시간. 해시 계산 시간은 포함하지 않는다
    - retryAfter: 대기 시간 안에 차례가 오지 않아 503으로 거부할 때 Retry-After 헤더에 담는 값
    값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
 */
@ConfigurationProperties(prefix = "grab.password-hash")
public record PasswordHashProperties(
        int maxConcurrent,
        Duration acquireTimeout,
        Duration retryAfter
) {

    public PasswordHashProperties(int maxConcurrent, Duration acquireTimeout, Duration retryAfter) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("grab.password-hash.max-concurrent 값은 1 이상이어야 합니다.");
        }
        requireAcquireTimeout(acquireTimeout);
        requireRetryAfter(retryAfter);
        this.maxConcurrent = maxConcurrent;
        this.acquireTimeout = acquireTimeout;
        this.retryAfter = retryAfter;
    }

    // Semaphore.tryAcquire에 나노초로 넘기므로, 나노초로 표현할 수 없는 기간도 기동 시점에 막는다
    private static void requireAcquireTimeout(Duration value) {
        try {
            if (value != null && !value.isNegative() && value.toMillis() >= 1 && value.toNanos() > 0) {
                return;
            }
        } catch (ArithmeticException ignored) {
            // 나노초로 바꿀 수 없을 만큼 긴 기간이다. 아래에서 잘못된 설정으로 거부한다
        }
        throw new IllegalArgumentException("grab.password-hash.acquire-timeout 값은 나노초로 표현할 수 있고 1밀리초 이상이어야 합니다.");
    }

    // Retry-After 헤더는 정수 초이고, BusinessException은 1초 미만을 거부한다. 1.5초처럼 잘리는 값은 의도와 달라지므로 받지 않는다
    private static void requireRetryAfter(Duration value) {
        if (value == null || value.getSeconds() < 1 || value.getNano() != 0) {
            throw new IllegalArgumentException("grab.password-hash.retry-after 값은 1초 이상의 정수 초여야 합니다.");
        }
    }
}
