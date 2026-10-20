package org.example.grab.domain.drop.stream.transport;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.stream.config.StockStreamProperties;
import org.example.grab.domain.drop.stream.config.StockStreamExecutors;
import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.model.StockStreamEvent;
import org.example.grab.domain.drop.stream.model.StockStreamEvent.CloseReason;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.domain.drop.stream.service.PublicDropSubscriptionService;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.example.grab.global.error.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
public class StockStreamHub implements SmartLifecycle {

    private final StockStreamProperties properties;
    private final StockStreamRegistry registry;
    private final PublicDropSubscriptionService publicDrops;
    private final ThreadPoolExecutor sender;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final LongAdder signals = new LongAdder();
    private final LongAdder merged = new LongAdder();
    private final LongAdder sent = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private volatile boolean stopping;
    private volatile boolean running;
    private ScheduledFuture<?> pump;

    public StockStreamHub(StockStreamProperties properties, StockStreamRegistry registry,
            PublicDropSubscriptionService publicDrops, StockStreamExecutors executors) {
        this.properties = properties;
        this.registry = registry;
        this.publicDrops = publicDrops;
        this.sender = executors.sender();
        this.scheduler = executors.scheduler();
    }

    public Metrics metrics() {
        int pending = 0;
        for (Connection connection : connections.values()) {
            synchronized (connection) {
                pending += connection.pending.size();
            }
        }
        return new Metrics(pending, signals.sum(), merged.sum(), sent.sum(), failures.sum(), timeouts.sum(), rejected.sum());
    }

    public record Metrics(int pending, long signals, long merged, long sent, long failures, long timeouts, long rejected) {
    }

    @Override
    public synchronized void start() {
        if (pump == null) {
            // 연결마다 타이머를 만들지 않아 종료 후 취소된 예약 작업이 쌓이지 않는다.
            running = true;
            pump = scheduler.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 웹 서버의 graceful shutdown이 장기 SSE 응답을 기다리기 전에 연결을 종료한다.
        return Integer.MAX_VALUE;
    }

    public SseEmitter open(StockStreamSubscription subscription) {
        return open(subscription, new SseEmitter(0L));
    }

    SseEmitter open(StockStreamSubscription subscription, SseEmitter emitter) {
        try {
            if (stopping || sender.isShutdown()) {
                throw new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE, retryAfterSeconds());
            }
            Connection connection = new Connection(subscription, emitter);
            emitter.onCompletion(() -> dispose(connection));
            emitter.onTimeout(() -> dispose(connection));
            emitter.onError(error -> dispose(connection));
            connections.put(subscription.id(), connection);
            if (stopping) {
                dispose(connection);
                throw new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE, retryAfterSeconds());
            }
            return emitter;
        } catch (RuntimeException error) {
            registry.unregister(subscription.id());
            throw error;
        }
    }

    // Servlet async 시작과 비차단 쓰기 준비 뒤에만 ready 전송을 허용한다.
    public void activate(UUID id, BooleanSupplier writable) {
        Connection connection = connections.get(id);
        if (connection != null) {
            synchronized (connection) {
                connection.writable = writable;
                connection.active = true;
            }
            tick();
        }
    }

    public void disconnect(UUID id) {
        Connection connection = connections.get(id);
        if (connection != null) {
            dispose(connection);
        }
    }

    // 커밋 이후 로컬 전달의 진입점이다. 호출 스레드에서는 DB 조회와 소켓 I/O를 하지 않는다.
    public void publish(long dropId, Set<StockChange> changes) {
        if (changes.isEmpty() || stopping) {
            return;
        }
        signals.increment();
        Set<StockChange> signal = EnumSet.copyOf(changes);
        for (StockStreamSubscription subscription : registry.findSubscribers(dropId)) {
            Connection connection = connections.get(subscription.id());
            if (connection == null) {
                continue;
            }
            synchronized (connection) {
                if (connection.closed || connection.closeReason != null) {
                    continue;
                }
                if (connection.pending.isEmpty()) {
                    connection.pendingDue = System.nanoTime() + properties.coalesceWindow().toNanos();
                }
                if (connection.pending.containsKey(dropId)) {
                    merged.increment();
                }
                connection.pending.computeIfAbsent(dropId, ignored -> EnumSet.noneOf(StockChange.class)).addAll(signal);
            }
        }
    }

    public void invalidate(UUID id) {
        requestClose(id, CloseReason.SUBSCRIPTION_INVALIDATED);
    }

    private void requestClose(UUID id, CloseReason reason) {
        Connection connection = connections.get(id);
        if (connection != null) {
            synchronized (connection) {
                connection.closeReason = reason;
                connection.pending.clear();
            }
        }
    }

    void tick() {
        long now = System.nanoTime();
        for (Connection connection : connections.values()) {
            boolean timedOut;
            synchronized (connection) {
                timedOut = connection.startedSending != 0
                        && now - connection.startedSending >= properties.sendTimeout().toNanos();
                if (!connection.active && now - connection.created >= TimeUnit.SECONDS.toNanos(10)) {
                    timedOut = true;
                }
            }
            if (timedOut) {
                timeouts.increment();
                dispose(connection);
                continue;
            }
            submitIfDue(connection, now);
        }
        registry.removeExpiredIps();
    }

    private long retryAfterSeconds() {
        return properties.retryAfter().getSeconds() + (properties.retryAfter().getNano() > 0 ? 1 : 0);
    }

    private void submitIfDue(Connection connection, long now) {
        FutureTask<Void> task;
        synchronized (connection) {
            if (connection.closed || !connection.active || connection.sending) {
                return;
            }
            if (connection.readySent && now - connection.expires >= 0) {
                connection.closeReason = CloseReason.RECONNECT_REQUIRED;
            }
            if (connection.readySent && connection.closeReason == null
                    && (connection.pending.isEmpty() || now - connection.pendingDue < 0)
                    && now - connection.heartbeatDue < 0) {
                return;
            }
            connection.sending = true;
            task = new FutureTask<>(() -> {
                send(connection);
                return null;
            });
            connection.task = task;
        }
        try {
            sender.execute(task);
        } catch (RejectedExecutionException error) {
            rejected.increment();
            // 스트림이 이미 시작됐을 수 있어 HTTP 오류로 바꾸지 않고 연결을 정리한다.
            dispose(connection);
        }
    }

    private void send(Connection connection) {
        try {
            boolean ready;
            CloseReason closeReason;
            List<StockStreamEvent.Changed> changes;
            synchronized (connection) {
                if (connection.closed) {
                    return;
                }
                connection.worker = Thread.currentThread();
                connection.startedSending = System.nanoTime();
                ready = !connection.readySent;
                closeReason = connection.closeReason;
                if (!ready && closeReason == null && !connection.pending.isEmpty()
                        && System.nanoTime() - connection.pendingDue >= 0) {
                    changes = connection.pending.entrySet().stream()
                            .map(entry -> new StockStreamEvent.Changed(entry.getKey(), entry.getValue())).toList();
                    connection.pending.clear();
                } else {
                    changes = List.of();
                }
            }
            if (ready) {
                write(connection, SseEmitter.event().name("ready").data(new StockStreamEvent.Ready(
                        connection.subscription.dropIds(), OffsetDateTime.now(ZoneOffset.UTC)), MediaType.APPLICATION_JSON));
                synchronized (connection) {
                    connection.readySent = true;
                    connection.expires = System.nanoTime() + properties.connectionLifetime().toNanos();
                    connection.heartbeatDue = System.nanoTime() + ThreadLocalRandom.current()
                            .nextLong(properties.heartbeatInterval().toNanos());
                }
            } else if (closeReason != null) {
                write(connection, SseEmitter.event().name("stream-closed").data(closeReason.event(), MediaType.APPLICATION_JSON));
                dispose(connection);
            } else if (!changes.isEmpty()) {
                try {
                    publicDrops.validatePublicDrops(connection.subscription.dropIds());
                } catch (BusinessException error) {
                    if (error.getErrorCode() == DropErrorCode.DROP_NOT_FOUND) {
                        requestClose(connection.subscription.id(), CloseReason.SUBSCRIPTION_INVALIDATED);
                        return;
                    }
                    throw error;
                }
                for (StockStreamEvent.Changed change : changes) {
                    write(connection, SseEmitter.event().name("drop-changed").data(change, MediaType.APPLICATION_JSON));
                }
            } else {
                write(connection, SseEmitter.event().comment(" heartbeat"));
            }
            synchronized (connection) {
                if (!ready) {
                    connection.heartbeatDue = System.nanoTime() + properties.heartbeatInterval().toNanos();
                }
            }
        } catch (IOException | RuntimeException error) {
            failures.increment();
            // 응답 시작 후 JSON 오류를 쓰지 않는다. payload와 개인정보는 로그에 남기지 않는다.
            log.debug("SSE 전송 종료: {}", error.getClass().getSimpleName());
            dispose(connection);
        } finally {
            synchronized (connection) {
                connection.startedSending = 0;
                connection.worker = null;
                connection.sending = false;
                connection.task = null;
            }
        }
    }

    private void write(Connection connection, SseEmitter.SseEventBuilder event) throws IOException {
        synchronized (connection) {
            if (connection.closed) {
                throw new IOException("종료된 재고 스트림입니다.");
            }
        }
        if (!connection.writable.getAsBoolean()) {
            throw new IOException("재고 스트림 수신자가 전송을 따라오지 못합니다.");
        }
        connection.emitter.send(event);
        sent.increment();
    }

    private void dispose(Connection connection) {
        synchronized (connection) {
            if (connection.closed) {
                return;
            }
            connection.closed = true;
            connection.pending.clear();
            if (connection.task != null && connection.worker != Thread.currentThread()) {
                connection.task.cancel(true);
                sender.remove(connection.task);
            }
        }
        connections.remove(connection.subscription.id(), connection);
        registry.unregister(connection.subscription.id());
        try {
            connection.emitter.complete();
        } catch (RuntimeException error) {
            // 전송 실패 후 컨테이너가 먼저 종료했더라도 로컬 자원 정리는 유지한다.
            log.debug("SSE 완료 처리 종료: {}", error.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        if (pump != null) {
            pump.cancel(false);
        }
        for (UUID id : connections.keySet()) {
            requestClose(id, CloseReason.RECONNECT_REQUIRED);
        }
        tick();
        sender.shutdown();
        try {
            sender.awaitTermination(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            for (Connection connection : connections.values()) {
                dispose(connection);
            }
            sender.shutdownNow();
        }
    }

    private static final class Connection {
        private final StockStreamSubscription subscription;
        private final SseEmitter emitter;
        private final long created = System.nanoTime();
        private final Map<Long, EnumSet<StockChange>> pending = new HashMap<>();
        private BooleanSupplier writable = () -> false;
        private boolean active;
        private boolean closed;
        private boolean sending;
        private boolean readySent;
        private long pendingDue;
        private long expires;
        private long heartbeatDue;
        private long startedSending;
        private CloseReason closeReason;
        private FutureTask<Void> task;
        private Thread worker;

        private Connection(StockStreamSubscription subscription, SseEmitter emitter) {
            this.subscription = subscription;
            this.emitter = emitter;
        }
    }
}
