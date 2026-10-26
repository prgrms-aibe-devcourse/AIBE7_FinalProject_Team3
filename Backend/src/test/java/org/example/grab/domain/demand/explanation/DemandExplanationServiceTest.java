package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class DemandExplanationServiceTest {

    private static final UUID PLAN_ID = UUID.fromString("4f0c9a52-1d6b-4f4e-9a57-8f6d2c1e0b11");
    private static final String INPUT = "{\"plan\":{\"expected\":126,\"recommended\":139}}";
    private static final DemandPlanSavedEvent EVENT = new DemandPlanSavedEvent(PLAN_ID, INPUT);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private DemandExplanationClient client;
    private DemandExplanationRecorder recorder;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        client = mock(DemandExplanationClient.class);
        recorder = mock(DemandExplanationRecorder.class);
        meterRegistry = new SimpleMeterRegistry();
        given(recorder.record(any(), any(), any(), any(), any())).willReturn(true);
    }

    @Test
    @DisplayName("검증을 통과한 설명은 모델·토큰과 함께 GENERATED로 기록하고 결과·시간·토큰 지표를 남긴다")
    void onPlanSaved_recordsGenerated() {
        // given
        given(client.generate(DemandExplanationPrompt.SYSTEM, INPUT)).willReturn(new Generation(
                "{\"summary\":\"예상 판매량은 126개, 준비 권장 수량은 139개입니다.\"}", "gemini-2.5-flash", 812));

        // when
        service(Runnable::run, Duration.ofSeconds(10)).onPlanSaved(EVENT);

        // then
        verify(recorder).record(eq(PLAN_ID), eq(ExplanationStatus.GENERATED),
                contains("\"summary\":\"예상 판매량은 126개"), eq("gemini-2.5-flash"), eq(812));
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "generated").count()).isEqualTo(1);
        assertThat(meterRegistry.timer("grab.demand.explanation.duration", "model", "gemini-2.5-flash").count())
                .isEqualTo(1);
        assertThat(meterRegistry.summary("grab.demand.explanation.tokens", "model", "gemini-2.5-flash")
                .totalAmount()).isEqualTo(812);
    }

    @Test
    @DisplayName("입력에 없는 숫자가 있는 설명은 원문 없이 DISCARDED로 기록한다")
    void onPlanSaved_discardsMismatchedNumbers() {
        given(client.generate(anyString(), anyString())).willReturn(
                new Generation("{\"summary\":\"예상 판매량은 130개입니다.\"}", "gemini-2.5-flash", 500));

        service(Runnable::run, Duration.ofSeconds(10)).onPlanSaved(EVENT);

        verify(recorder).record(eq(PLAN_ID), eq(ExplanationStatus.DISCARDED), isNull(), eq("gemini-2.5-flash"),
                eq(500));
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "discarded").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("호출 실패는 재시도하지 않고 FAILED로 기록한다")
    void onPlanSaved_recordsFailedOnError() {
        given(client.generate(anyString(), anyString())).willThrow(new IllegalStateException("quota"));

        service(Runnable::run, Duration.ofSeconds(10)).onPlanSaved(EVENT);

        verify(recorder).record(PLAN_ID, ExplanationStatus.FAILED, null, null, null);
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("시간 제한을 넘기면 응답을 기다리지 않고 FAILED로 기록한다")
    void onPlanSaved_recordsFailedOnTimeout() throws InterruptedException {
        // given: 응답하지 않는 호출
        CountDownLatch release = new CountDownLatch(1);
        given(client.generate(anyString(), anyString())).willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return new Generation("{\"summary\":\"늦은 응답\"}", "gemini-2.5-flash", 1);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // when
            service(executor::execute, Duration.ofMillis(100)).onPlanSaved(EVENT);

            // then
            verify(recorder, timeout(2000)).record(PLAN_ID, ExplanationStatus.FAILED, null, null, null);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("전용 풀이 가득 차 요청을 넘기지 못하면 FAILED로 기록한다")
    void onPlanSaved_recordsFailedWhenRejected() {
        service(task -> {
            throw new TaskRejectedException("가득 참");
        }, Duration.ofSeconds(10)).onPlanSaved(EVENT);

        verify(recorder).record(PLAN_ID, ExplanationStatus.FAILED, null, null, null);
    }

    private DemandExplanationService service(org.springframework.core.task.TaskExecutor executor, Duration timeout) {
        return new DemandExplanationService(client, recorder, new DemandExplanationProperties(timeout, 1024, 0),
                objectMapper, executor, meterRegistry);
    }
}
