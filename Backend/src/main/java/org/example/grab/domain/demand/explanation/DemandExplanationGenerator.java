package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
    수요 분석 설명(DEMAND.md 3절)과 생성 단계 추천 근거 설명(8절)이 함께 쓰는 LLM 호출·검증.
    - 전용 풀에 호출을 넘기고 시간 제한까지 기다린다. 넘기거나 실패·대기열 가득 참이면 FAILED. 재시도하지 않는다.
    - 형식·수치 검증에 실패하면 DISCARDED, 통과하면 GENERATED.
    - 지표는 stage(wish·form)로 나눠 센다. 저장·재사용·한도는 호출하는 서비스가 맡는다.
 */
@Slf4j
@Component
public class DemandExplanationGenerator {

    public static final String STAGE_WISH = "wish";
    public static final String STAGE_FORM = "form";

    // 검증을 거친 결과. explanation은 GENERATED일 때만 있다. FAILED면 model·tokens도 없다
    public record Outcome(ExplanationStatus status, DemandExplanation explanation, String model, Integer tokens) {
    }

    private final DemandExplanationClient client;
    private final DemandExplanationProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;

    public DemandExplanationGenerator(
            DemandExplanationClient client,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier(DemandExplanationConfig.DEMAND_EXPLANATION_TASK_EXECUTOR) TaskExecutor executor,
            MeterRegistry meterRegistry
    ) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    public boolean available() {
        return client.available();
    }

    public Outcome generate(String system, String input, String stage) {
        long startedAt = System.nanoTime();
        Generation generation;
        try {
            generation = call(system, input);
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // 키·응답 원문이 로그에 남지 않도록 예외 종류만 남긴다
            log.warn("설명 생성 실패: stage={}, cause={}", stage, cause.getClass().getSimpleName());
            count("failed", stage);
            return new Outcome(ExplanationStatus.FAILED, null, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            count("failed", stage);
            return new Outcome(ExplanationStatus.FAILED, null, null, null);
        }
        String model = tagValue(generation.model());
        Timer.builder("grab.demand.explanation.duration")
                .tag("model", model)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
        if (generation.tokens() != null) {
            DistributionSummary.builder("grab.demand.explanation.tokens")
                    .tag("model", model)
                    .register(meterRegistry)
                    .record(generation.tokens());
        }
        Optional<DemandExplanation> explanation = generation.text() == null
                ? Optional.empty()
                : DemandExplanationValidator.validate(generation.text(), input, objectMapper);
        ExplanationStatus status = explanation.isPresent() ? ExplanationStatus.GENERATED : ExplanationStatus.DISCARDED;
        if (status == ExplanationStatus.DISCARDED) {
            log.info("설명 폐기(형식·수치 불일치): stage={}", stage);
        }
        count(status.name().toLowerCase(Locale.ROOT), stage);
        return new Outcome(status, explanation.orElse(null), generation.model(), generation.tokens());
    }

    public void countReused(String stage) {
        count("reused", stage);
    }

    // 프롬프트를 바꾸면 같은 입력이라도 새로 만들도록 버전을 함께 넣는다(DEMAND.md 3.4·8.4)
    public static String inputHash(String promptVersion, String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((promptVersion + "\n" + input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    // 대기열이 차면 RejectedExecutionException(FAILED). 시간을 넘기면 결과를 버리고 작업을 취소한다.
    // 진행 중인 HTTP 요청은 멈추지 않을 수 있어 풀 크기가 동시 호출의 상한이다
    private Generation call(String system, String input)
            throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Generation> future = CompletableFuture.supplyAsync(
                () -> client.generate(system, input), executor::execute);
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void count(String outcome, String stage) {
        Counter.builder("grab.demand.explanation")
                .tag("outcome", outcome)
                .tag("stage", stage)
                .register(meterRegistry)
                .increment();
    }

    private static String tagValue(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
