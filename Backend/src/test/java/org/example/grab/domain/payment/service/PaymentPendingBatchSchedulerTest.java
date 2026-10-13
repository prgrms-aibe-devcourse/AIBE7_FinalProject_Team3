package org.example.grab.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class PaymentPendingBatchSchedulerTest {

    @Mock
    private PaymentInProgressCleanupService paymentInProgressCleanupService;

    @Mock
    private PaymentExpiryService paymentExpiryService;

    @InjectMocks
    private PaymentPendingBatchScheduler scheduler;

    @Test
    @DisplayName("진행 중 결제 정리를 만료보다 먼저 실행하고, 두 작업에 같은 UTC 현재 시각을 전달한다")
    void cleansUpBeforeExpiryWithSameUtcNow() {
        // when
        scheduler.run();

        // then
        ArgumentCaptor<OffsetDateTime> cleanUpNow = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> expiryNow = ArgumentCaptor.forClass(OffsetDateTime.class);
        InOrder order = inOrder(paymentInProgressCleanupService, paymentExpiryService);
        order.verify(paymentInProgressCleanupService).cleanUp(cleanUpNow.capture());
        order.verify(paymentExpiryService).expire(expiryNow.capture());
        assertThat(cleanUpNow.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(expiryNow.getValue()).isEqualTo(cleanUpNow.getValue());
    }

    @Test
    @DisplayName("진행 중 결제 정리에서 예외가 나도 만료는 실행한다")
    void expiresEvenIfCleanUpFails() {
        // given
        given(paymentInProgressCleanupService.cleanUp(any())).willThrow(new RuntimeException("일시적 DB 오류"));

        // when & then
        assertThatCode(scheduler::run).doesNotThrowAnyException();
        then(paymentExpiryService).should().expire(any());
    }

    @Test
    @DisplayName("만료 중 예외가 나도 밖으로 전파하지 않아 다음 실행이 이어진다")
    void swallowsException() {
        // given
        given(paymentExpiryService.expire(any())).willThrow(new RuntimeException("일시적 DB 오류"));

        // when & then
        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }

    /*
        grab.payment-pending-batch.enabled 값에 따라 스케줄러 빈이 등록되는지 검증한다.
        Gradle test 태스크의 enabled=false 기본값을 여기서만 덮어쓴다.
     */
    @Test
    @DisplayName("속성이 true면 스케줄러 빈이 등록되고, false면 등록되지 않는다")
    void registersByProperty() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(SchedulerTestConfig.class)
                .withBean(ScheduledAnnotationBeanPostProcessor.class);

        contextRunner.withPropertyValues("grab.payment-pending-batch.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PaymentPendingBatchScheduler.class));
        contextRunner.withPropertyValues("grab.payment-pending-batch.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentPendingBatchScheduler.class));
    }

    // @Import로 등록하면 클래스의 @ConditionalOnProperty가 평가된다(@Bean 직접 등록은 평가되지 않는다).
    @Configuration
    @Import(PaymentPendingBatchScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        PaymentInProgressCleanupService paymentInProgressCleanupService() {
            return mock(PaymentInProgressCleanupService.class);
        }

        @Bean
        PaymentExpiryService paymentExpiryService() {
            return mock(PaymentExpiryService.class);
        }
    }
}
