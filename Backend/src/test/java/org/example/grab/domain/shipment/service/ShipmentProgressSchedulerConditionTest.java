package org.example.grab.domain.shipment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * grab.mock-delivery.enabled 값에 따라 스케줄러 빈이 등록되는지 검증한다(GR-86).
 * MockDeliveryController와 같은 플래그를 쓰므로, 그 플래그가 꺼져 있으면 이 스케줄러도 등록되지 않아야 한다.
 */
class ShipmentProgressSchedulerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfig.class)
            .withBean(ScheduledAnnotationBeanPostProcessor.class);

    @Test
    @DisplayName("속성이 true면 스케줄러 빈이 등록된다")
    void registersWhenEnabled() {
        contextRunner
                .withPropertyValues("grab.mock-delivery.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ShipmentProgressScheduler.class));
    }

    @Test
    @DisplayName("속성이 false거나 없으면 스케줄러 빈이 등록되지 않는다")
    void skipsWhenDisabled() {
        contextRunner
                .withPropertyValues("grab.mock-delivery.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ShipmentProgressScheduler.class));
    }

    // @Import로 등록하면 클래스의 @ConditionalOnProperty가 평가된다(@Bean 직접 등록은 평가되지 않는다).
    @Configuration
    @Import(ShipmentProgressScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        ShipmentProgressService shipmentProgressService() {
            return mock(ShipmentProgressService.class);
        }
    }
}
