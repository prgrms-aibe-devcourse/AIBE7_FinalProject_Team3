package org.example.grab.domain.drop.stream.transport;

import org.example.grab.domain.drop.stream.config.StockStreamProperties;
import org.example.grab.domain.drop.stream.config.StockStreamExecutors;
import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.example.grab.global.error.BusinessException;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class StockStreamHubTest {

    private StockStreamRegistry registry;
    private StockStreamHub hub;
    private ThreadPoolExecutor sender;
    private ScheduledThreadPoolExecutor scheduler;

    @BeforeEach
    void setUp() {
        StockStreamProperties properties = new StockStreamProperties(Duration.ofMillis(10), Duration.ofHours(1),
                Duration.ofMinutes(10), Duration.ofMillis(100), 100, 100, 200, 20, Duration.ofMinutes(1),
                30, Duration.ofMinutes(1), 10000);
        registry = new StockStreamRegistry(properties, Clock.systemUTC());
        sender = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200));
        scheduler = new ScheduledThreadPoolExecutor(1);
        hub = new StockStreamHub(properties, registry, new StockStreamExecutors(sender, scheduler));
    }

    @AfterEach
    void tearDown() {
        hub.stop();
        sender.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("등록 중 변경을 유한하게 합치고 ready 뒤에 한 번 전달한다")
    void sendsReadyBeforeMergedChanges() throws Exception {
        // given
        SseEmitter emitter = mock(SseEmitter.class);
        BlockingQueue<String> frames = capture(emitter);
        StockStreamSubscription subscription = register(emitter);
        for (int i = 0; i < 1000; i++) {
            hub.publish(1, Set.of(StockChange.STOCK));
            hub.publish(1, Set.of(StockChange.SALES));
        }
        hub.publish(999, Set.of(StockChange.STATUS));
        assertThat(frames).isEmpty();

        // when
        hub.activate(subscription.id(), () -> true);

        // then
        assertThat(next(frames)).contains("event:ready").doesNotContain("event:drop-changed");
        await(() -> {
            hub.tick();
            return frames.stream().anyMatch(frame -> frame.contains("drop-changed"));
        });
        assertThat(next(frames)).contains("event:drop-changed", "STOCK", "SALES").doesNotContain("999");
    }

    @Test
    @DisplayName("전송 오류가 다른 구독자를 막지 않고 종료를 반복해도 한 번만 정리한다")
    void isolatesWriteFailuresAndRepeatedDisconnects() throws Exception {
        // given
        SseEmitter failing = mock(SseEmitter.class);
        doThrow(new IOException("테스트 전송 실패")).when(failing).send(any(SseEmitter.SseEventBuilder.class));
        StockStreamSubscription first = register(failing);
        SseEmitter healthy = mock(SseEmitter.class);
        BlockingQueue<String> frames = capture(healthy);
        StockStreamSubscription second = register(healthy);

        // when
        hub.activate(first.id(), () -> true);
        hub.activate(second.id(), () -> true);

        // then
        assertThat(next(frames)).contains("ready");
        await(() -> registry.countConnections() == 1);
        hub.disconnect(first.id());
        hub.disconnect(first.id());
        verify(failing, timeout(1000).times(1)).complete();
        assertThat(registry.findSubscribers(1)).containsExactly(second);
    }

    @Test
    @DisplayName("Servlet이 쓰기 불가를 알리면 기다리지 않고 연결·슬롯을 정리한다")
    void rejectsSlowReceiverWithoutBlockingWrites() throws Exception {
        // given
        SseEmitter emitter = mock(SseEmitter.class);
        StockStreamSubscription subscription = register(emitter);

        // when
        hub.activate(subscription.id(), () -> false);

        // then
        await(() -> registry.countConnections() == 0);
        verify(emitter, times(0)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, timeout(1000)).complete();
    }

    @Test
    @DisplayName("전송 작업 정체를 감지해 구독을 해제하고 다른 연결은 계속 전송한다")
    void timesOutStalledSendAndKeepsOtherConnectionWorking() throws Exception {
        // given
        SseEmitter stalled = mock(SseEmitter.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(stalled).send(any(SseEmitter.SseEventBuilder.class));
        StockStreamSubscription first = register(stalled);
        SseEmitter healthy = mock(SseEmitter.class);
        BlockingQueue<String> frames = capture(healthy);
        StockStreamSubscription second = register(healthy);
        try {
            // when
            hub.activate(first.id(), () -> true);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            hub.activate(second.id(), () -> true);

            // then
            assertThat(next(frames)).contains("ready");
            await(() -> {
                hub.tick();
                return registry.countConnections() == 1;
            });
            assertThat(registry.findSubscribers(1)).containsExactly(second);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("송신 작업 큐가 거절돼도 연결 슬롯을 반환한다")
    void cleansUpRejectedSend() throws Exception {
        // given
        SseEmitter emitter = mock(SseEmitter.class);
        StockStreamSubscription subscription = register(emitter);
        sender.shutdown();

        // when
        hub.activate(subscription.id(), () -> true);

        // then
        assertThat(registry.countConnections()).isZero();
        verify(emitter, timeout(1000)).complete();
    }

    @Test
    @DisplayName("종료된 전송 기반에서 open 실패는 사전 예약한 구독도 반환한다")
    void releasesSubscriptionWhenOpeningFails() {
        // given
        StockStreamSubscription subscription = registry.register("a", List.of(1L));
        sender.shutdown();

        // when & then
        assertThatThrownBy(() -> hub.open(subscription)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(DropErrorCode.STOCK_STREAM_UNAVAILABLE);
            assertThat(error.getRetryAfterSeconds()).isEqualTo(60);
        });
        assertThat(registry.countConnections()).isZero();
    }

    @Test
    @DisplayName("서버 종료는 종료 안내를 시도하고 모든 구독·작업을 정리한다")
    void releasesAllOnShutdown() throws Exception {
        // given
        SseEmitter emitter = mock(SseEmitter.class);
        BlockingQueue<String> frames = capture(emitter);
        StockStreamSubscription subscription = register(emitter);
        hub.activate(subscription.id(), () -> true);
        assertThat(next(frames)).contains("ready");
        await(() -> sender.getActiveCount() == 0);

        // when
        hub.stop();

        // then
        assertThat(next(frames)).contains("RECONNECT_REQUIRED");
        assertThat(registry.countConnections()).isZero();
        assertThat(registry.findSubscribers(1)).isEmpty();
        assertThat(sender.getQueue()).isEmpty();
    }

    private StockStreamSubscription register(SseEmitter emitter) {
        StockStreamSubscription subscription = registry.register("a", List.of(1L));
        hub.open(subscription, emitter);
        return subscription;
    }

    private BlockingQueue<String> capture(SseEmitter emitter) throws Exception {
        BlockingQueue<String> frames = new ArrayBlockingQueue<>(100);
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder event = invocation.getArgument(0);
            frames.add(event.build().stream().map(item -> String.valueOf(item.getData())).collect(Collectors.joining()));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return frames;
    }

    private String next(BlockingQueue<String> frames) throws Exception {
        String frame = frames.poll(2, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        return frame;
    }

    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(2);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
