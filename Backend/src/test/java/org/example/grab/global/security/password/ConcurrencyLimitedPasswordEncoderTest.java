package org.example.grab.global.security.password;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/*
    GR-178 M03-01: 비밀번호 해시 동시 실행 상한(M00-01·M00-04). 실제 Argon2 대신 문을 닫아 두면 멈추는 가짜 인코더로
    해시가 진행 중인 상태를 만든다. 시간에 기대지 않도록 "자리를 잡았다"·"기다린다"는 신호를 확인한 뒤 다음 단계로 간다.
 */
class ConcurrencyLimitedPasswordEncoderTest {

    private static final Duration LONG_WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT_WAIT = Duration.ofMillis(50);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GatedEncoder delegate = new GatedEncoder();

    @AfterEach
    void tearDown() {
        // 실패한 테스트가 문을 닫은 채 끝나도 스레드가 남지 않게 한다
        delegate.open();
        executor.shutdownNow();
    }

    private static ConcurrencyLimitedPasswordEncoder encoder(PasswordEncoder delegate, int maxConcurrent, Duration acquireTimeout) {
        return new ConcurrencyLimitedPasswordEncoder(delegate, new PasswordHashProperties(maxConcurrent, acquireTimeout, RETRY_AFTER));
    }

    @Test
    @DisplayName("동시 실행이 상한을 넘지 않고, 넘친 요청은 자리가 날 때까지 기다렸다가 처리된다")
    void limitsConcurrentHashesAndQueuesTheRest() throws Exception {
        // given: 상한 2, 해시가 끝나지 않도록 문을 닫는다
        ConcurrencyLimitedPasswordEncoder encoder = encoder(delegate, 2, LONG_WAIT);
        delegate.close();

        // when: 3건을 동시에 보낸다
        List<Future<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(executor.submit(() -> encoder.matches("raw", "hash")));
        }

        // then: 2건만 해시에 들어가고 1건은 기다린다
        delegate.awaitEntered(2);
        awaitUntil(() -> encoder.waitingCount() == 1);
        assertThat(delegate.running()).isEqualTo(2);
        assertThat(delegate.enteredWithin(Duration.ofMillis(200))).isFalse();

        // when: 해시를 끝내면 기다리던 요청도 자리를 얻어 처리된다
        delegate.open();

        // then
        for (Future<Boolean> call : calls) {
            assertThat(call.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
        assertThat(delegate.peak()).isEqualTo(2);
        assertThat(encoder.waitingCount()).isZero();
        assertThat(encoder.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("대기 시간 안에 자리가 나지 않으면 해시하지 않고 AUTH_TEMPORARILY_UNAVAILABLE과 Retry-After로 거부한다")
    void rejectsWhenNoPermitWithinTimeout() throws Exception {
        // given: 상한 1을 다른 요청이 쓰고 있다
        ConcurrencyLimitedPasswordEncoder encoder = encoder(delegate, 1, SHORT_WAIT);
        delegate.close();
        Future<Boolean> holder = executor.submit(() -> encoder.matches("raw", "hash"));
        delegate.awaitEntered(1);

        // when & then
        assertThatThrownBy(() -> encoder.encode("raw"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_TEMPORARILY_UNAVAILABLE);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(3L);
                });
        assertThat(delegate.encodeCalls()).isZero();
        assertThat(encoder.rejectedCount()).isEqualTo(1);

        // 거부된 요청은 자리를 가져가지 않았으므로 쓰던 요청을 끝내면 다음 요청이 바로 처리된다
        delegate.open();
        assertThat(holder.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(encoder.encode("raw")).isEqualTo("encoded:raw");
    }

    @Test
    @DisplayName("위임한 인코더가 예외를 던져도 자리를 반납한다")
    void releasesPermitWhenDelegateThrows() {
        // given: 상한 1, matches가 예외를 던진다
        ConcurrencyLimitedPasswordEncoder encoder = encoder(delegate, 1, SHORT_WAIT);
        delegate.failMatches();

        // when
        assertThatThrownBy(() -> encoder.matches("raw", "hash")).isInstanceOf(IllegalStateException.class);

        // then: 자리가 남아 있어 다음 해시가 대기 없이 처리된다
        assertThat(encoder.encode("raw")).isEqualTo("encoded:raw");
        assertThat(encoder.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("차례를 기다리는 중 인터럽트되면 해시하지 않고 거부하며 인터럽트 상태를 남긴다")
    void rejectsAndKeepsInterruptStatusWhenInterruptedWhileWaiting() throws Exception {
        // given: 상한 1을 다른 요청이 쓰고, 두 번째 요청이 기다린다
        ConcurrencyLimitedPasswordEncoder encoder = encoder(delegate, 1, LONG_WAIT);
        delegate.close();
        executor.submit(() -> encoder.matches("raw", "hash"));
        delegate.awaitEntered(1);

        AtomicBoolean interruptedAfterReject = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                encoder.encode("raw");
            } catch (BusinessException e) {
                rejected.set(e.getErrorCode() == CommonErrorCode.AUTH_TEMPORARILY_UNAVAILABLE);
                interruptedAfterReject.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        awaitUntil(() -> encoder.waitingCount() == 1);

        // when
        waiter.interrupt();
        waiter.join(LONG_WAIT.toMillis());

        // then
        assertThat(waiter.isAlive()).isFalse();
        assertThat(rejected).isTrue();
        assertThat(interruptedAfterReject).isTrue();
        assertThat(delegate.encodeCalls()).isZero();
        assertThat(encoder.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("encode·matches 결과를 그대로 돌려주고, upgradeEncoding은 자리 없이 위임한다")
    void delegatesResultsAndUpgradeEncodingWithoutPermit() throws Exception {
        // given
        ConcurrencyLimitedPasswordEncoder encoder = encoder(delegate, 1, LONG_WAIT);

        // when & then: 위임 결과가 같다
        assertThat(encoder.encode("raw")).isEqualTo("encoded:raw");
        assertThat(encoder.matches("raw", "encoded:raw")).isTrue();
        assertThat(encoder.matches("other", "encoded:raw")).isFalse();

        // given: 상한 1을 다른 요청이 쓰고 있다. 앞의 호출이 남긴 진입 신호를 비워, 이번 요청이 자리를 잡은 뒤에 진행한다
        delegate.resetEntered();
        delegate.close();
        executor.submit(() -> encoder.matches("raw", "hash"));
        delegate.awaitEntered(1);
        assertThat(delegate.running()).isEqualTo(1);

        // when & then: 자리가 없어도 upgradeEncoding은 기다리지 않고 위임 결과를 돌려준다
        Future<Boolean> upgrade = executor.submit(() -> encoder.upgradeEncoding("old-hash"));
        assertThat(upgrade.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(encoder.upgradeEncoding("encoded:raw")).isFalse();
        assertThat(encoder.waitingCount()).isZero();
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + LONG_WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("조건이 " + LONG_WAIT + " 안에 충족되지 않았다");
            }
            Thread.sleep(5);
        }
    }

    // 문이 닫혀 있으면 해시 도중에 멈춰 "해시가 진행 중인" 상태를 만든다. 진입 수와 동시 실행 최댓값을 기록한다
    private static final class GatedEncoder implements PasswordEncoder {

        private volatile CountDownLatch gate = new CountDownLatch(0);
        private final Semaphore entered = new Semaphore(0);
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger encodeCalls = new AtomicInteger();
        private volatile boolean failMatches;

        void close() {
            gate = new CountDownLatch(1);
        }

        void open() {
            gate.countDown();
        }

        void failMatches() {
            failMatches = true;
        }

        void resetEntered() {
            entered.drainPermits();
        }

        void awaitEntered(int count) throws InterruptedException {
            assertThat(entered.tryAcquire(count, LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("해시 %d건이 시작되어야 한다", count).isTrue();
        }

        boolean enteredWithin(Duration wait) throws InterruptedException {
            return entered.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        int running() {
            return running.get();
        }

        int peak() {
            return peak.get();
        }

        int encodeCalls() {
            return encodeCalls.get();
        }

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls.incrementAndGet();
            return hash(() -> "encoded:" + rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (failMatches) {
                throw new IllegalStateException("위임 인코더 실패");
            }
            return hash(() -> ("encoded:" + rawPassword).equals(encodedPassword)) || "hash".equals(encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return "old-hash".equals(encodedPassword);
        }

        private <T> T hash(Supplier<T> result) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            entered.release();
            try {
                if (!gate.await(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("문이 열리지 않았다");
                }
                return result.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
