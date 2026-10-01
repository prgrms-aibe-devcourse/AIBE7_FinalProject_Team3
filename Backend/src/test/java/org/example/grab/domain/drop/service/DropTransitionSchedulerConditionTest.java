package org.example.grab.domain.drop.service;

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
 * grab.drop-transition.enabled 값에 따라 스케줄러 빈이 등록되는지 검증한다.
 * 빈 활성화 자체를 검증하는 테스트라 Gradle test 태스크의 enabled=false 기본값을 이 테스트에서만 덮어쓴다.
 */
class DropTransitionSchedulerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfig.class)
            .withBean(ScheduledAnnotationBeanPostProcessor.class);

    @Test
    @DisplayName("속성이 true면 스케줄러 빈이 등록된다")
    void registersWhenEnabled() {
        contextRunner
                .withPropertyValues("grab.drop-transition.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DropTransitionScheduler.class));
    }

    @Test
    @DisplayName("속성이 false면 스케줄러 빈이 등록되지 않는다")
    void skipsWhenDisabled() {
        contextRunner
                .withPropertyValues("grab.drop-transition.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DropTransitionScheduler.class));
    }

    // @Import로 등록하면 클래스의 @ConditionalOnProperty가 평가된다(@Bean 직접 등록은 평가되지 않는다).
    @Configuration
    @Import(DropTransitionScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        DropTransitionService dropTransitionService() {
            return mock(DropTransitionService.class);
        }
    }
}
