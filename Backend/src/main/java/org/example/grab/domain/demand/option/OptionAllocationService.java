package org.example.grab.domain.demand.option;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.dto.OptionAllocationRequest;
import org.example.grab.domain.demand.dto.OptionAllocationRequest.OptionInput;
import org.example.grab.domain.demand.dto.OptionAllocationResponse;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.MatchStatus;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.OptionAllocation;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationConfig;
import org.example.grab.domain.demand.option.OptionAllocationCalculator.GroupResult;
import org.example.grab.domain.demand.option.OptionCandidates.Candidate;
import org.example.grab.domain.demand.option.OptionMatchClient.Generation;
import org.example.grab.domain.demand.repository.OptionMatchRepository;
import org.example.grab.domain.demand.service.StockRecommendationService;
import org.example.grab.domain.demand.service.StockRecommendationService.Calculation;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

/*
    판매자가 설정한 옵션 안에서 준비 권장 수량을 비슷한 상품의 옵션별 판매 비율로 나눈다(DEMAND.md 7절).
    검색(표본 옵션 후보) → 글자 매칭 → LLM 후보 번호 매칭 → 서버 계산 순서이며, LLM은 같은 값 찾기만 한다.
    LLM 호출이 실패해도 예외로 끝내지 않고 글자 매칭 결과로 계산한다(matchStatus로 표시).
 */
@Slf4j
@Service
@EnableConfigurationProperties(OptionMatchProperties.class)
public class OptionAllocationService {

    static final String LIMIT_SCOPE = "stock:option-match";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockRecommendationService stockRecommendationService;
    private final OptionMatchRepository repository;
    private final OptionMatchClient client;
    private final DailyRequestLimitRepository limitRepository;
    private final OptionMatchProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public OptionAllocationService(
            StockRecommendationService stockRecommendationService,
            OptionMatchRepository repository,
            OptionMatchClient client,
            DailyRequestLimitRepository limitRepository,
            OptionMatchProperties properties,
            ObjectMapper objectMapper,
            @Qualifier(DemandExplanationConfig.DEMAND_EXPLANATION_TASK_EXECUTOR) TaskExecutor executor,
            MeterRegistry meterRegistry
    ) {
        this(stockRecommendationService, repository, client, limitRepository, properties, objectMapper, executor,
                meterRegistry, Clock.systemUTC());
    }

    OptionAllocationService(
            StockRecommendationService stockRecommendationService,
            OptionMatchRepository repository,
            OptionMatchClient client,
            DailyRequestLimitRepository limitRepository,
            OptionMatchProperties properties,
            ObjectMapper objectMapper,
            TaskExecutor executor,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.stockRecommendationService = stockRecommendationService;
        this.repository = repository;
        this.client = client;
        this.limitRepository = limitRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public OptionAllocationResponse allocate(long sellerId, OptionAllocationRequest request) {
        List<OptionGroupInput> groups = request.optionGroups();
        int[][] skuValues = skuValues(groups, request.options());
        Calculation calculation = stockRecommendationService.calculateWithSamples(sellerId,
                request.toRecommendationRequest());
        StockRecommendationResponse recommendation = calculation.response();
        Integer recommended = recommendation.recommendation().recommended();
        if (recommendation.basis() == null || recommended == null) {
            List<GroupResult> even = OptionAllocationCalculator.groups(groups, OptionMatches.empty(groups),
                    List.of(), null);
            return new OptionAllocationResponse(null, recommendation.sampleSelection(), null, null,
                    even.stream().map(GroupResult::allocation).toList(),
                    IntStream.range(0, skuValues.length).mapToObj(i -> new OptionAllocation(i, null)).toList());
        }

        List<Long> dropIds = calculation.basisSamples().stream().map(SalesSample::dropId).toList();
        List<Candidate> candidates = OptionCandidates.collect(repository.findOptionSales(dropIds),
                properties.maxCandidates());
        OptionMatches textMatches = OptionMatches.byText(groups, candidates);
        Matching matching = match(sellerId, groups, candidates, textMatches);
        count(matching.status().name().toLowerCase(Locale.ROOT));

        List<GroupResult> groupResults = OptionAllocationCalculator.groups(groups,
                matching.matches().withoutSharedCandidates(), candidates, recommendation.basis());
        List<OptionAllocation> options;
        if (OptionAllocationCalculator.allEven(groupResults)) {
            // 모든 그룹이 균등이면 5.2의 옵션별 배분과 같다
            options = recommendation.recommendation().options().stream()
                    .map(option -> new OptionAllocation(option.index(), option.recommendedQuantity()))
                    .toList();
        } else {
            int[] quantities = OptionAllocationCalculator.allocate(recommended, groupResults, skuValues);
            options = IntStream.range(0, quantities.length)
                    .mapToObj(i -> new OptionAllocation(i, quantities[i]))
                    .toList();
        }
        return new OptionAllocationResponse(recommendation.basis(), recommendation.sampleSelection(), recommended,
                matching.status(), groupResults.stream().map(GroupResult::allocation).toList(), options);
    }

    private record Matching(OptionMatches matches, MatchStatus status) {
    }

    private Matching match(long sellerId, List<OptionGroupInput> groups, List<Candidate> candidates,
                           OptionMatches textMatches) {
        if (textMatches.complete() || candidates.isEmpty()) {
            return new Matching(textMatches, MatchStatus.TEXT_ONLY);
        }
        if (!client.available()) {
            return new Matching(textMatches, MatchStatus.DISABLED);
        }
        String input = OptionMatchPrompt.input(groups, candidates, objectMapper);
        String inputHash = inputHash(input);
        Optional<OptionMatches> stored = repository.findMatches(inputHash)
                .flatMap(json -> OptionMatches.parse(json, groups, candidates.size(), objectMapper));
        if (stored.isPresent()) {
            return new Matching(textMatches.union(stored.get()), MatchStatus.REUSED);
        }
        // LLM을 실제로 부르는 요청만 하루 한도로 센다(7.2)
        if (limitRepository.increment(LIMIT_SCOPE, sellerId, OffsetDateTime.now(clock).atZoneSameInstant(KST)
                .toLocalDate()) > properties.dailyLimit()) {
            throw new BusinessException(DemandErrorCode.STOCK_OPTION_MATCH_LIMIT_EXCEEDED);
        }
        Generation generation;
        try {
            generation = call(input);
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // 판매자 입력이 로그에 남지 않도록 예외 종류만 남긴다
            log.warn("옵션 매칭 실패: cause={}", cause.getClass().getSimpleName());
            return new Matching(textMatches, MatchStatus.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Matching(textMatches, MatchStatus.FAILED);
        }
        Optional<OptionMatches> parsed = generation.text() == null ? Optional.empty()
                : OptionMatches.parse(generation.text(), groups, candidates.size(), objectMapper);
        if (parsed.isEmpty()) {
            log.info("옵션 매칭 응답 폐기(형식 오류)");
            return new Matching(textMatches, MatchStatus.DISCARDED);
        }
        repository.saveMatches(inputHash, parsed.get().toJson(objectMapper), generation.model(),
                OptionMatchPrompt.VERSION, OffsetDateTime.now(clock));
        return new Matching(textMatches.union(parsed.get()), MatchStatus.GENERATED);
    }

    // 시간 제한을 넘기면 결과를 버리고 작업을 취소한다. 대기열이 차면 RejectedExecutionException(FAILED)
    private Generation call(String input) throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Generation> future = CompletableFuture.supplyAsync(
                () -> client.generate(OptionMatchPrompt.SYSTEM, input), executor::execute);
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /*
        SKU마다 옵션 구성 순서대로 값 번호를 찾는다. 개수가 다르거나 그룹에 없는 값이면 VALIDATION_FAILED(options).
     */
    static int[][] skuValues(List<OptionGroupInput> groups, List<OptionInput> options) {
        Set<String> uniqueGroups = new HashSet<>();
        for (OptionGroupInput group : groups) {
            if (group.values().isEmpty() || !uniqueGroups.add(OptionCandidates.normalize(group.name()))) {
                throw invalid("optionGroups", "옵션 그룹은 값이 하나 이상이어야 하고 이름이 겹치면 안 됩니다.");
            }
        }
        int[][] skuValues = new int[options.size()][];
        for (int i = 0; i < options.size(); i++) {
            List<String> values = options.get(i).values();
            if (values.size() != groups.size()) {
                throw invalid("options", "SKU 값은 옵션 그룹 순서대로 그룹마다 하나여야 합니다.");
            }
            skuValues[i] = new int[groups.size()];
            for (int g = 0; g < groups.size(); g++) {
                int index = groups.get(g).values().indexOf(values.get(g));
                if (index < 0) {
                    throw invalid("options", "SKU 값이 옵션 그룹에 없습니다.");
                }
                skuValues[i][g] = index;
            }
        }
        return skuValues;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                List.of(new ErrorResponse.FieldError(field, message)));
    }

    static String inputHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (OptionMatchPrompt.VERSION + "\n" + input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private void count(String outcome) {
        meterRegistry.counter("grab.demand.option-match", "outcome", outcome).increment();
    }
}
