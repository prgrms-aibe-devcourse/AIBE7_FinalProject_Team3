package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.example.grab.domain.demand.repository.DemandExplanationLimitRepository;
import org.example.grab.domain.demand.repository.DemandExplanationRepository;
import org.example.grab.domain.demand.repository.DemandExplanationRepository.StoredExplanation;
import org.example.grab.domain.demand.service.DemandAnalysisService;
import org.example.grab.global.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
    판매자가 요청할 때 그 판매자가 고른 가정의 분석 결과를 설명한다(DEMAND.md 3절).
    - 같은 DROP·같은 입력(프롬프트 버전+입력 JSON의 해시)으로 만든 설명이 있으면 LLM을 부르지 않고 다시 쓴다.
    - LLM을 부를 때만 판매자별 하루 한도를 센다. 넘으면 DEMAND_EXPLANATION_LIMIT_EXCEEDED.
    - 전용 풀에 호출을 넘기고 시간 제한(기본 10초)까지 기다린다. 넘기거나 실패·대기열 가득 참이면 FAILED,
      형식·수치 검증에 실패하면 DISCARDED, 통과하면 GENERATED. 재시도하지 않는다.
    - GENERATED·DISCARDED만 저장한다. FAILED는 남기지 않아 다음 요청에서 다시 시도한다.
    - LLM 응답을 기다리는 동안 DB 트랜잭션을 잡지 않는다(분석 계산과 저장은 각각 짧게 끝난다).
 */
@Slf4j
@Service
public class DemandExplanationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DemandAnalysisService demandAnalysisService;
    private final DemandExplanationClient client;
    private final DemandExplanationRepository explanationRepository;
    private final DemandExplanationLimitRepository limitRepository;
    private final DemandExplanationProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public DemandExplanationService(
            DemandAnalysisService demandAnalysisService,
            DemandExplanationClient client,
            DemandExplanationRepository explanationRepository,
            DemandExplanationLimitRepository limitRepository,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier(DemandExplanationConfig.DEMAND_EXPLANATION_TASK_EXECUTOR) TaskExecutor executor,
            MeterRegistry meterRegistry
    ) {
        this(demandAnalysisService, client, explanationRepository, limitRepository, properties, objectMapper,
                executor, meterRegistry, Clock.systemUTC());
    }

    DemandExplanationService(
            DemandAnalysisService demandAnalysisService,
            DemandExplanationClient client,
            DemandExplanationRepository explanationRepository,
            DemandExplanationLimitRepository limitRepository,
            DemandExplanationProperties properties,
            ObjectMapper objectMapper,
            TaskExecutor executor,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.demandAnalysisService = demandAnalysisService;
        this.client = client;
        this.explanationRepository = explanationRepository;
        this.limitRepository = limitRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public DemandExplanationResponse explain(long sellerId, long dropId, DemandCalculateRequest request) {
        // 권한·소유권·상태 검사와 계산은 분석 조회와 같다(DEMAND.md 2절)
        DemandAnalysisResponse analysis = demandAnalysisService.analyze(sellerId, dropId, request);
        if (!client.available()) {
            return DemandExplanationResponse.of(ExplanationStatus.DISABLED);
        }
        String input = DemandExplanationPrompt.input(analysis, objectMapper);
        String inputHash = inputHash(input);
        Optional<StoredExplanation> stored = explanationRepository.find(dropId, inputHash);
        if (stored.isPresent()) {
            count("reused");
            return toResponse(stored.get());
        }
        long requests = limitRepository.increment(sellerId, OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate());
        if (requests > properties.dailyLimit()) {
            throw new BusinessException(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED);
        }
        return generate(dropId, inputHash, input);
    }

    private DemandExplanationResponse generate(long dropId, String inputHash, String input) {
        long startedAt = System.nanoTime();
        Generation generation;
        try {
            generation = call(input);
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // 키·응답 원문이 로그에 남지 않도록 예외 종류만 남긴다
            log.warn("수요 분석 설명 생성 실패: dropId={}, cause={}", dropId, cause.getClass().getSimpleName());
            count("failed");
            return DemandExplanationResponse.of(ExplanationStatus.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            count("failed");
            return DemandExplanationResponse.of(ExplanationStatus.FAILED);
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
            log.info("수요 분석 설명 폐기(형식·수치 불일치): dropId={}", dropId);
        }
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        explanationRepository.save(dropId, inputHash, status.name(),
                explanation.map(objectMapper::writeValueAsString).orElse(null),
                generation.model(), DemandExplanationPrompt.VERSION, generation.tokens(), generatedAt);
        count(status.name().toLowerCase(Locale.ROOT));
        return new DemandExplanationResponse(status, explanation.orElse(null), generatedAt);
    }

    // 대기열이 차면 RejectedExecutionException(FAILED). 시간을 넘기면 결과를 버리고 작업을 취소한다.
    // 진행 중인 HTTP 요청은 멈추지 않을 수 있어 풀 크기가 동시 호출의 상한이다
    private Generation call(String input) throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Generation> future = CompletableFuture.supplyAsync(
                () -> client.generate(DemandExplanationPrompt.SYSTEM, input), executor::execute);
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private DemandExplanationResponse toResponse(StoredExplanation stored) {
        ExplanationStatus status = ExplanationStatus.valueOf(stored.status());
        DemandExplanation explanation = stored.explanationJson() == null
                ? null
                : objectMapper.readValue(stored.explanationJson(), DemandExplanation.class);
        return new DemandExplanationResponse(status, explanation, stored.createdAt());
    }

    // 프롬프트를 바꾸면 같은 입력이라도 새로 만들도록 버전을 함께 넣는다(DEMAND.md 3.4)
    static String inputHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((DemandExplanationPrompt.VERSION + "\n" + input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private void count(String outcome) {
        Counter.builder("grab.demand.explanation")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private static String tagValue(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
