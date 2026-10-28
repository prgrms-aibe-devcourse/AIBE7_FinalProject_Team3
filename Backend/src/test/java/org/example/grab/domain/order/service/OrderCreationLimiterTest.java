package org.example.grab.domain.order.service;

import org.example.grab.domain.order.config.OrderCreationLimitProperties;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/*
    GR-202: 주문 생성 동시 실행 상한. 테스트 방식은 ConcurrencyLimitedPasswordEncoderTest(GR-178)를 따른다 —
    문(gate)을 닫아 작업이 끝나지 않는 상태를 만들고, 진입 신호로 타이밍에 기대지 않고 확인한다.
 */
class OrderCreationLimiterTest {

    private static final Duration LONG_WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT_WAIT = Duration.ofMillis(50);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private final Semaphore entered = new Semaphore(0);
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @AfterEach
    void tearDown() {
        gate.countDown();
        executor.shutdownNow();
    }

    private static OrderCreationLimiter limiter(int maxConcurrent, Duration acquireTimeout) {
        return new OrderCreationLimiter(new OrderCreationLimitProperties(maxConcurrent, acquireTimeout, RETRY_AFTER));
    }

    private Integer work() {
        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
        entered.release();
        try {
            if (!gate.await(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("문이 열리지 않았다");
            }
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            running.decrementAndGet();
        }
    }

    @Test
    @DisplayName("동시 실행이 상한을 넘지 않고, 넘친 요청은 자리가 날 때까지 기다렸다가 처리된다")
    void limitsConcurrentCreationsAndQueuesTheRest() throws Exception {
        // given: 상한 2, 작업이 끝나지 않도록 문을 닫는다
        OrderCreationLimiter limiter = limiter(2, LONG_WAIT);
        gate = new CountDownLatch(1);

        // when: 3건을 동시에 보낸다
        List<Future<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(executor.submit(() -> limiter.withPermit(this::work)));
        }

        // then: 2건만 진입하고 1건은 기다린다
        assertThat(entered.tryAcquire(2, LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        awaitUntil(() -> limiter.waitingCount() == 1);
        assertThat(running.get()).isEqualTo(2);

        // when: 문을 열면 기다리던 요청도 자리를 얻어 처리된다
        gate.countDown();

        // then
        for (Future<Integer> call : calls) {
            assertThat(call.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
        }
        assertThat(peak.get()).isEqualTo(2);
        assertThat(limiter.waitingCount()).isZero();
        assertThat(limiter.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("대기 시간 안에 자리가 나지 않으면 실행하지 않고 ORDER_CREATION_TEMPORARILY_UNAVAILABLE과 Retry-After로 거부한다")
    void rejectsWhenNoPermitWithinTimeout() throws Exception {
        // given: 상한 1을 다른 요청이 쓰고 있다
        OrderCreationLimiter limiter = limiter(1, SHORT_WAIT);
        gate = new CountDownLatch(1);
        Future<Integer> holder = executor.submit(() -> limiter.withPermit(this::work));
        assertThat(entered.tryAcquire(1, LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // when & then
        assertThatThrownBy(() -> limiter.withPermit(() -> 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CREATION_TEMPORARILY_UNAVAILABLE);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(3L);
                });
        assertThat(limiter.rejectedCount()).isEqualTo(1);

        // 거부된 요청은 자리를 가져가지 않았으므로 문을 열면 쓰던 요청이 바로 끝난다
        gate.countDown();
        assertThat(holder.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
    }

    @Test
    @DisplayName("작업이 예외를 던져도 자리를 반납한다")
    void releasesPermitWhenActionThrows() {
        // given: 상한 1
        OrderCreationLimiter limiter = limiter(1, SHORT_WAIT);

        // when
        assertThatThrownBy(() -> limiter.withPermit(() -> {
            throw new IllegalStateException("실패");
        })).isInstanceOf(IllegalStateException.class);

        // then: 자리가 남아 있어 다음 작업이 대기 없이 처리된다
        assertThat(limiter.withPermit(() -> 1)).isEqualTo(1);
        assertThat(limiter.rejectedCount()).isZero();
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
}
