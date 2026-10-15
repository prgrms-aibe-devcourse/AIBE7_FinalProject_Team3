package org.example.grab.domain.drop.stream.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.example.grab.domain.drop.stream.transport.StockStreamHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StockStreamMetricsConfigTest {
    @Test
    @DisplayName("SSE 연결 수명만 일반 HTTP 지연 계측에서 제외한다")
    void excludesOnlyStreamHttpLatency() {
        // given
        var filter = new StockStreamMetricsConfig().stockStreamHttpMetricsFilter();
        var stream = new Meter.Id("http.server.requests", Tags.of("uri", "/api/v1/drops/stock-stream"), null, null, Meter.Type.TIMER);
        var detail = new Meter.Id("http.server.requests", Tags.of("uri", "/api/v1/drops/{dropId}"), null, null, Meter.Type.TIMER);

        // when / then
        assertThat(filter.accept(stream)).isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(detail)).isEqualTo(MeterFilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("연결·상품 구독·거절·멱등 정리 지표는 개인정보 태그 없이 실제 상태를 반영한다")
    void observesBoundedRegistryState() {
        // given
        var properties = new StockStreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofMinutes(10),
                Duration.ofSeconds(5), 100, 100, 200, 1, Duration.ofSeconds(60), 30, Duration.ofSeconds(60), 10000);
        var subscriptions = new StockStreamRegistry(properties, Clock.systemUTC());
        var hub = mock(StockStreamHub.class);
        when(hub.metrics()).thenReturn(new StockStreamHub.Metrics(0, 2, 1, 1, 0, 0, 0));
        var meters = new SimpleMeterRegistry();
        try {
            new StockStreamMetricsConfig().stockStreamMetrics(subscriptions, hub).bindTo(meters);

            // when
            var subscription = subscriptions.register("127.0.0.1", List.of(1L, 2L));
            assertThatThrownBy(() -> subscriptions.register("127.0.0.1", List.of(1L))).isInstanceOf(RuntimeException.class);

            // then
            assertThat(meters.get("grab.stock.stream.connections").gauge().value()).isEqualTo(1);
            assertThat(meters.get("grab.stock.stream.subscriptions").gauge().value()).isEqualTo(2);
            assertThat(meters.get("grab.stock.stream.rejected").tag("reason", "ip").functionCounter().count()).isEqualTo(1);
            subscriptions.unregister(subscription.id());
            subscriptions.unregister(subscription.id());
            assertThat(meters.get("grab.stock.stream.connections").gauge().value()).isZero();
            assertThat(meters.get("grab.stock.stream.subscriptions").gauge().value()).isZero();
            assertThat(meters.get("grab.stock.stream.disposed").functionCounter().count()).isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("reason")));
        } finally {
            meters.close();
        }
    }
}
