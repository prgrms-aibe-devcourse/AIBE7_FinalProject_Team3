package org.example.grab.domain.drop.stream.config;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

// Executor 타입 자체를 빈으로 노출하면 Boot의 공용 스케줄러 자동 설정을 대체하므로 전용 소유자 안에 둔다.
@RequiredArgsConstructor
public final class StockStreamExecutors implements AutoCloseable {

    private final ThreadPoolExecutor sender;
    private final ScheduledThreadPoolExecutor scheduler;

    public ThreadPoolExecutor sender() {
        return sender;
    }

    public ScheduledThreadPoolExecutor scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        sender.shutdownNow();
        scheduler.shutdownNow();
    }
}
