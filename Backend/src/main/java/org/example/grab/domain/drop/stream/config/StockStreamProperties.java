package org.example.grab.domain.drop.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("grab.stock-stream")
public record StockStreamProperties(
        @DefaultValue("1s") Duration coalesceWindow,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("10m") Duration connectionLifetime,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("100") int maxPendingDrops,
        @DefaultValue("100") int maxDropIds,
        @DefaultValue("1000") int maxConnections,
        @DefaultValue("50") int maxConnectionsPerIp,
        @DefaultValue("60s") Duration connectRateWindow,
        @DefaultValue("75") int connectRateMax,
        @DefaultValue("60s") Duration retryAfter,
        @DefaultValue("10000") int maxTrackedIps
) {
    public StockStreamProperties {
        requireDuration(coalesceWindow, "coalesce-window");
        requireDuration(heartbeatInterval, "heartbeat-interval");
        requireDuration(connectionLifetime, "connection-lifetime");
        requireDuration(sendTimeout, "send-timeout");
        requireDuration(connectRateWindow, "connect-rate-window");
        requireDuration(retryAfter, "retry-after");
        if (maxDropIds < 1 || maxDropIds > 100 || maxPendingDrops < maxDropIds
                || maxPendingDrops > 100 || maxConnections < 1 || maxConnectionsPerIp < 1
                || connectRateMax < 1 || maxTrackedIps < 1) {
            throw new IllegalArgumentException("grab.stock-stream의 개수 설정이 허용 범위를 벗어났습니다.");
        }
    }

    private static void requireDuration(Duration value, String name) {
        try {
            if (value != null && !value.isNegative() && value.toMillis() > 0 && value.toNanos() > 0) {
                return;
            }
        } catch (ArithmeticException ignored) {
            // 타이머가 표현할 수 없는 기간도 잘못된 설정으로 기동 시 거부한다.
        }
        throw new IllegalArgumentException("grab.stock-stream." + name + "은 나노초로 표현 가능하고 1밀리초 이상이어야 합니다.");
    }
}
