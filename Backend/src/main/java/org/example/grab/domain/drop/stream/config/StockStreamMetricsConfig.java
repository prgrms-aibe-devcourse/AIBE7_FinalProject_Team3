package org.example.grab.domain.drop.stream.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.example.grab.domain.drop.stream.transport.StockStreamHub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockStreamMetricsConfig {
    @Bean
    public MeterFilter stockStreamHttpMetricsFilter() {
        // SSE 수명은 일반 HTTP 처리 지연에 합산하지 않는다. 연결 상태는 전용 지표로 관측한다.
        return MeterFilter.deny(id -> id.getName().equals("http.server.requests")
                && "/api/v1/drops/stock-stream".equals(id.getTag("uri")));
    }

    @Bean
    public MeterBinder stockStreamMetrics(StockStreamRegistry subscriptions, StockStreamHub hub) {
        return registry -> {
            Gauge.builder("grab.stock.stream.connections", subscriptions, value -> value.metrics().connections()).register(registry);
            Gauge.builder("grab.stock.stream.subscriptions", subscriptions, value -> value.metrics().subscriptions()).register(registry);
            Gauge.builder("grab.stock.stream.pending", hub, value -> value.metrics().pending()).register(registry);
            FunctionCounter.builder("grab.stock.stream.rejected", subscriptions, value -> value.metrics().rateRejected())
                    .tag("reason", "rate").register(registry);
            FunctionCounter.builder("grab.stock.stream.rejected", subscriptions, value -> value.metrics().ipRejected())
                    .tag("reason", "ip").register(registry);
            FunctionCounter.builder("grab.stock.stream.rejected", subscriptions, value -> value.metrics().capacityRejected())
                    .tag("reason", "capacity").register(registry);
            FunctionCounter.builder("grab.stock.stream.rejected", subscriptions, value -> value.metrics().trackingRejected())
                    .tag("reason", "tracking").register(registry);
            FunctionCounter.builder("grab.stock.stream.disposed", subscriptions, value -> value.metrics().disposed()).register(registry);
            FunctionCounter.builder("grab.stock.stream.signals", hub, value -> value.metrics().signals()).register(registry);
            FunctionCounter.builder("grab.stock.stream.merged", hub, value -> value.metrics().merged()).register(registry);
            FunctionCounter.builder("grab.stock.stream.sent", hub, value -> value.metrics().sent()).register(registry);
            FunctionCounter.builder("grab.stock.stream.failures", hub, value -> value.metrics().failures()).register(registry);
            FunctionCounter.builder("grab.stock.stream.timeouts", hub, value -> value.metrics().timeouts()).register(registry);
            FunctionCounter.builder("grab.stock.stream.queue.rejected", hub, value -> value.metrics().rejected()).register(registry);
        };
    }
}
