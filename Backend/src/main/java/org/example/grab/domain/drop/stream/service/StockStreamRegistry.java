package org.example.grab.domain.drop.stream.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.stream.config.StockStreamProperties;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.global.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
public class StockStreamRegistry {

    private final StockStreamProperties properties;
    private final Clock clock;
    private final Map<UUID, Entry> subscriptions = new HashMap<>();
    private final Map<Long, Set<UUID>> subscribersByDrop = new HashMap<>();
    private final Map<String, IpState> ipStates = new HashMap<>();

    private long rateRejected;
    private long ipRejected;
    private long capacityRejected;
    private long trackingRejected;
    private long disposed;

    public synchronized Metrics metrics() {
        return new Metrics(subscriptions.size(), subscribersByDrop.values().stream().mapToInt(Set::size).sum(),
                rateRejected, ipRejected, capacityRejected, trackingRejected, disposed);
    }

    public record Metrics(int connections, int subscriptions, long rateRejected, long ipRejected,
            long capacityRejected, long trackingRejected, long disposed) {
    }

    // 모든 상태 변경을 같은 잠금으로 묶어 admission과 중복 종료 사이 슬롯/인덱스 불일치를 막는다.
    public synchronized void recordAttempt(String remoteAddress) {
        Instant now = clock.instant();
        removeExpiredIps(now);
        IpState state = getIpState(remoteAddress, now);
        if (isExpired(state, now)) {
            state.windowStartedAt = now;
            state.attempts = 0;
        }
        if (state.attempts >= properties.connectRateMax()) {
            rateRejected++;
            throw new BusinessException(DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED, retryAfterSeconds());
        }
        state.attempts++;
    }

    public synchronized StockStreamSubscription register(String remoteAddress, List<Long> dropIds) {
        if (dropIds.isEmpty() || dropIds.size() > properties.maxDropIds()
                || new HashSet<>(dropIds).size() != dropIds.size()
                || dropIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("정규화된 구독 상품 ID가 필요합니다.");
        }
        Instant now = clock.instant();
        removeExpiredIps(now);
        IpState state = getIpState(remoteAddress, now);
        if (state.connections >= properties.maxConnectionsPerIp()) {
            ipRejected++;
            throw new BusinessException(DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED, retryAfterSeconds());
        }
        if (subscriptions.size() >= properties.maxConnections()) {
            capacityRejected++;
            throw new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE, retryAfterSeconds());
        }
        StockStreamSubscription subscription = new StockStreamSubscription(UUID.randomUUID(), dropIds);
        subscriptions.put(subscription.id(), new Entry(remoteAddress, subscription));
        for (Long dropId : subscription.dropIds()) {
            subscribersByDrop.computeIfAbsent(dropId, ignored -> new HashSet<>()).add(subscription.id());
        }
        state.connections++;
        return subscription;
    }

    public synchronized boolean unregister(UUID subscriptionId) {
        Entry entry = subscriptions.remove(subscriptionId);
        if (entry == null) {
            return false;
        }
        for (Long dropId : entry.subscription().dropIds()) {
            Set<UUID> ids = subscribersByDrop.get(dropId);
            ids.remove(subscriptionId);
            if (ids.isEmpty()) {
                subscribersByDrop.remove(dropId);
            }
        }
        ipStates.get(entry.remoteAddress()).connections--;
        disposed++;
        removeExpiredIps(clock.instant());
        return true;
    }

    public synchronized List<StockStreamSubscription> findSubscribers(long dropId) {
        return subscribersByDrop.getOrDefault(dropId, Set.of()).stream()
                .map(id -> subscriptions.get(id).subscription())
                .toList();
    }

    public synchronized int countConnections() {
        return subscriptions.size();
    }

    public synchronized int countTrackedIps() {
        removeExpiredIps(clock.instant());
        return ipStates.size();
    }

    public synchronized void removeExpiredIps() {
        removeExpiredIps(clock.instant());
    }

    private long retryAfterSeconds() {
        return properties.retryAfter().getSeconds() + (properties.retryAfter().getNano() > 0 ? 1 : 0);
    }

    private IpState getIpState(String remoteAddress, Instant now) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            throw new IllegalArgumentException("검증된 remoteAddr가 필요합니다.");
        }
        IpState state = ipStates.get(remoteAddress);
        if (state == null) {
            if (ipStates.size() >= properties.maxTrackedIps()) {
                trackingRejected++;
                throw new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE, retryAfterSeconds());
            }
            state = new IpState(now);
            ipStates.put(remoteAddress, state);
        }
        return state;
    }

    private boolean isExpired(IpState state, Instant now) {
        return !now.isBefore(state.windowStartedAt.plus(properties.connectRateWindow()));
    }

    private void removeExpiredIps(Instant now) {
        // 활성 연결의 IP는 남기고, 실패 요청만 남은 고정 창은 TTL이 지나면 제거한다.
        ipStates.values().removeIf(state -> state.connections == 0 && isExpired(state, now));
    }

    private record Entry(String remoteAddress, StockStreamSubscription subscription) {
    }

    private static final class IpState {
        private Instant windowStartedAt;
        private int attempts;
        private int connections;

        private IpState(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
