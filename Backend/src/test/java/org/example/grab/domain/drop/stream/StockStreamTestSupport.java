package org.example.grab.domain.drop.stream;

import org.example.grab.domain.drop.stream.config.StockStreamProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class StockStreamTestSupport {

    private StockStreamTestSupport() {
    }

    public static StockStreamProperties properties(int connections, int perIp, int attempts, int trackedIps) {
        return new StockStreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofMinutes(10),
                Duration.ofSeconds(5), 100, 100, connections, perIp, Duration.ofSeconds(60), attempts,
                Duration.ofSeconds(60), trackedIps);
    }

    public static StockStreamProperties defaults() {
        return properties(200, 20, 30, 10000);
    }

    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));

        public void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
