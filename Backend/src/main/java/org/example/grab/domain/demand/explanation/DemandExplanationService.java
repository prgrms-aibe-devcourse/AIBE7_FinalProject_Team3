package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/*
    계획 저장이 커밋된 뒤 설명을 한 번 생성한다(DEMAND.md 3절). 커밋 전에 호출하면 롤백된 계획의 설명을 만들 수 있고,
    저장 응답이 LLM 응답 시간만큼 늦어지므로 AFTER_COMMIT에서 전용 풀로 넘긴다.
    - 시간 제한(기본 10초)을 넘기거나 호출이 실패하면 FAILED, 형식·수치 검증에 실패하면 DISCARDED, 통과하면 GENERATED.
    - 재시도하지 않는다. 같은 계획에는 다시 생성하지 않는다(기록은 PENDING에서 한 번만 된다).
    - 시간 제한을 넘긴 호출의 HTTP 요청은 멈추지 않고 결과만 버린다. 풀 크기(2)가 동시에 붙잡히는 스레드의 상한이다.
    - 어떤 실패도 계획 저장·조회·결정에 영향을 주지 않는다. 남은 PENDING은 2분 뒤 응답에서 FAILED로 보인다.
 */
@Slf4j
@Component
public class DemandExplanationService {

    private final DemandExplanationClient client;
    private final DemandExplanationRecorder recorder;
    private final DemandExplanationProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;

    public DemandExplanationService(
            DemandExplanationClient client,
            DemandExplanationRecorder recorder,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier(DemandExplanationConfig.DEMAND_EXPLANATION_TASK_EXECUTOR) TaskExecutor executor,
            MeterRegistry meterRegistry
    ) {
        this.client = client;
        this.recorder = recorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanSaved(DemandPlanSavedEvent event) {
        long startedAt = System.nanoTime();
        CompletableFuture<Generation> call;
        try {
            call = CompletableFuture.supplyAsync(
                    () -> client.generate(DemandExplanationPrompt.SYSTEM, event.input()), executor::execute);
        } catch (RejectedExecutionException e) {
            log.warn("판매 계획 설명 요청 거부(대기열 가득 참): planId={}", event.planId());
            record(event, ExplanationStatus.FAILED, null, null);
            return;
        }
        call.orTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((generation, error) -> complete(event, generation, error, startedAt));
    }

    private void complete(DemandPlanSavedEvent event, Generation generation, Throwable error, long startedAt) {
        try {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                // 키·응답 원문이 로그에 남지 않도록 예외 종류만 남긴다
                log.warn("판매 계획 설명 생성 실패: planId={}, cause={}", event.planId(), cause.getClass().getSimpleName());
                record(event, ExplanationStatus.FAILED, null, null);
                return;
            }
            String model = generation.model();
            Timer.builder("grab.demand.explanation.duration")
                    .tag("model", tagValue(model))
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
            if (generation.tokens() != null) {
                DistributionSummary.builder("grab.demand.explanation.tokens")
                        .tag("model", tagValue(model))
                        .register(meterRegistry)
                        .record(generation.tokens());
            }
            Optional<DemandExplanation> explanation = generation.text() == null
                    ? Optional.empty()
                    : DemandExplanationValidator.validate(generation.text(), event.input(), objectMapper);
            if (explanation.isPresent()) {
                record(event, ExplanationStatus.GENERATED, objectMapper.writeValueAsString(explanation.get()),
                        generation);
            } else {
                log.info("판매 계획 설명 폐기(형식·수치 불일치): planId={}", event.planId());
                record(event, ExplanationStatus.DISCARDED, null, generation);
            }
        } catch (RuntimeException e) {
            log.warn("판매 계획 설명 기록 실패: planId={}, cause={}", event.planId(), e.getClass().getSimpleName());
        }
    }

    private void record(DemandPlanSavedEvent event, ExplanationStatus outcome, String explanationJson,
                        Generation generation) {
        boolean recorded = recorder.record(event.planId(), outcome, explanationJson,
                generation == null ? null : generation.model(),
                generation == null ? null : generation.tokens());
        if (recorded) {
            Counter.builder("grab.demand.explanation")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static String tagValue(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
