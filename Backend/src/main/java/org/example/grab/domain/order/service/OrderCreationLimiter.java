package org.example.grab.domain.order.service;

import org.example.grab.domain.order.config.OrderCreationLimitProperties;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.global.error.BusinessException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/*
    주문 생성을 동시에 정해진 수까지만 실행한다(GR-202). 드롭 전체에 걸리는 전역 상한 하나다 —
    스파이크를 일으킨 드롭 한 종류 때문에 다른 드롭의 주문까지 같이 거부될 수 있지만,
    지금 문제(CPU 포화가 무관한 트래픽 전체를 느리게 만듦)를 막는 데는 이 정도로 충분하고
    드롭별 상한보다 훨씬 단순하다(ConcurrencyLimitedPasswordEncoder와 같은 설계, GR-178).
 */
public class OrderCreationLimiter {

    private final Semaphore permits;
    private final long acquireTimeoutNanos;
    private final long retryAfterSeconds;
    private final AtomicLong rejected = new AtomicLong();

    public OrderCreationLimiter(OrderCreationLimitProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrent(), true);
        this.acquireTimeoutNanos = properties.acquireTimeout().toNanos();
        this.retryAfterSeconds = properties.retryAfter().getSeconds();
    }

    public <T> T withPermit(Supplier<T> action) {
        acquire();
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    // 거부된 요청 수(지표용)
    public long rejectedCount() {
        return rejected.get();
    }

    // 지금 기다리는 요청 수. Semaphore.getQueueLength()는 Javadoc상 추정값이라 대기 추이 참고용으로만 쓴다
    public int waitingCount() {
        return permits.getQueueLength();
    }

    private void acquire() {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rejected();
        }
        if (!acquired) {
            throw rejected();
        }
    }

    private BusinessException rejected() {
        rejected.incrementAndGet();
        return new BusinessException(OrderErrorCode.ORDER_CREATION_TEMPORARILY_UNAVAILABLE, retryAfterSeconds);
    }
}
