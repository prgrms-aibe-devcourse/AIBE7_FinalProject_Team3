package org.example.grab.domain.order.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.example.grab.domain.order.service.OrderCreationLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    주문 생성 동시 실행 상한의 관측 지표(GR-202). 거부 규모와 대기 추이를 지표로 본다.
    등록 방식은 PasswordHashMetricsConfig(GR-178)를 따른다.
 */
@Configuration
public class OrderCreationLimiterMetricsConfig {

    @Bean
    public MeterBinder orderCreationLimiterMetrics(OrderCreationLimiter orderCreationLimiter) {
        return registry -> {
            FunctionCounter.builder("grab.order.creation.rejected", orderCreationLimiter,
                    OrderCreationLimiter::rejectedCount).register(registry);
            Gauge.builder("grab.order.creation.waiting", orderCreationLimiter,
                    OrderCreationLimiter::waitingCount).register(registry);
        };
    }
}
