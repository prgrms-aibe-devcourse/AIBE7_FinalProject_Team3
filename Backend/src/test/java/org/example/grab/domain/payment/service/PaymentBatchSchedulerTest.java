package org.example.grab.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class PaymentBatchSchedulerTest {

    @Mock
    private PaymentBatchService paymentBatchService;

    @InjectMocks
    private PaymentBatchScheduler scheduler;

    @Test
    @DisplayName("UTC 현재 시각을 만들어 결제 배치에 전달한다")
    void passesUtcNowToBatch() {
        // when
        scheduler.run();

        // then
        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        then(paymentBatchService).should().run(captor.capture());
        assertThat(captor.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("배치에서 예외가 나도 밖으로 전파하지 않아 다음 실행이 이어진다")
    void swallowsException() {
        // given
        willThrow(new RuntimeException("일시적 오류")).given(paymentBatchService).run(any());

        // when & then
        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }

    /*
        grab.payment-batch.enabled 값에 따라 스케줄러 빈이 등록되는지 검증한다.
        Gradle test 태스크의 enabled=false 기본값을 여기서만 덮어쓴다.
     */
    @Test
    @DisplayName("속성이 true면 스케줄러 빈이 등록되고, false면 등록되지 않는다")
    void registersByProperty() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(SchedulerTestConfig.class)
                .withBean(ScheduledAnnotationBeanPostProcessor.class);

        contextRunner.withPropertyValues("grab.payment-batch.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PaymentBatchScheduler.class));
        contextRunner.withPropertyValues("grab.payment-batch.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentBatchScheduler.class));
    }

    // @Import로 등록하면 클래스의 @ConditionalOnProperty가 평가된다(@Bean 직접 등록은 평가되지 않는다).
    @Configuration
    @Import(PaymentBatchScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        PaymentBatchService paymentBatchService() {
            return mock(PaymentBatchService.class);
        }
    }
}
