package org.example.grab.domain.drop.stream.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StockStreamPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StockStreamConfig.class);

    @Test
    @DisplayName("기본 설정을 바인딩하고 구독 저장소 빈을 생성한다")
    void bindsDefaults() {
        // when & then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StockStreamProperties properties = context.getBean(StockStreamProperties.class);
            assertThat(properties.coalesceWindow()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.connectionLifetime()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.maxDropIds()).isEqualTo(100);
            assertThat(properties.maxConnections()).isEqualTo(1000);
            assertThat(properties.maxConnectionsPerIp()).isEqualTo(50);
            assertThat(properties.connectRateMax()).isEqualTo(75);
            assertThat(properties.connectRateWindow()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.retryAfter()).isEqualTo(Duration.ofSeconds(60));
            assertThat(context).hasBean("stockStreamRegistry");
            assertThat(context).hasSingleBean(StockStreamExecutors.class);
            assertThat(context).doesNotHaveBean(java.util.concurrent.Executor.class);
            assertThat(context).doesNotHaveBean(java.util.concurrent.ScheduledExecutorService.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-connections=0", "max-connections-per-ip=-1", "max-drop-ids=101",
            "max-drop-ids=0", "max-pending-drops=99", "max-pending-drops=101", "max-tracked-ips=0",
            "connect-rate-max=0", "coalesce-window=0s", "heartbeat-interval=-1s", "send-timeout=0.1ms",
            "connection-lifetime=0s", "connect-rate-window=0s", "retry-after=0s", "coalesce-window=365000d"})
    @DisplayName("잘못된 개수·기간 설정은 기동할 때 거부한다")
    void rejectsInvalidSettings(String setting) {
        // when & then
        runner.withPropertyValues("grab.stock-stream." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("기본값을 배포 설정으로 덮어쓸 수 있다")
    void overridesDefaults() {
        // when & then
        runner.withPropertyValues("grab.stock-stream.max-connections=50", "grab.stock-stream.coalesce-window=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StockStreamProperties.class).maxConnections()).isEqualTo(50);
                    assertThat(context.getBean(StockStreamProperties.class).coalesceWindow()).isEqualTo(Duration.ofSeconds(2));
                });
    }
}
