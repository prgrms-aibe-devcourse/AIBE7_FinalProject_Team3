package org.example.grab.domain.drop.aidraft;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.drop.aidraft.DropAiDraftClient.DraftImage;
import org.example.grab.domain.drop.aidraft.DropAiDraftClient.Generation;
import org.example.grab.domain.drop.aidraft.DropAiDraftResponse.ImageAltText;
import org.example.grab.domain.drop.aidraft.DropAiDraftValidator.Draft;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

/*
    업로드한 상품 사진으로 상품명·설명·카테고리·대체 설명 초안을 만든다(DROP.md 2.8, DROP-010).
    저장하지 않고 폼 값도 바꾸지 않는다. 판매자가 화면에서 항목별로 적용해야 폼에 들어간다.
    순서: 이미지 URL 확인 → 모델 사용 가능 확인 → 하루 한도 → 이미지 내려받기 → 시간 제한 안에서 호출 → 항목별 검증.
    한도는 요청 수로 세므로 실패한 호출도 포함한다. 재시도하지 않는다.
 */
@Slf4j
@Service
public class DropAiDraftService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 하루 한도 Redis 키 접두사(DailyRequestLimitRepository)
    static final String LIMIT_SCOPE = "drop:ai-draft";

    private final DropAiDraftClient client;
    private final DropAiDraftImageLoader imageLoader;
    private final DailyRequestLimitRepository limitRepository;
    private final CategoryService categoryService;
    private final DropAiDraftProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public DropAiDraftService(
            DropAiDraftClient client,
            DropAiDraftImageLoader imageLoader,
            DailyRequestLimitRepository limitRepository,
            CategoryService categoryService,
            DropAiDraftProperties properties,
            ObjectMapper objectMapper,
            @Qualifier(DropAiDraftConfig.DROP_AI_DRAFT_TASK_EXECUTOR) TaskExecutor executor,
            MeterRegistry meterRegistry
    ) {
        this(client, imageLoader, limitRepository, categoryService, properties, objectMapper, executor, meterRegistry,
                Clock.systemUTC());
    }

    DropAiDraftService(
            DropAiDraftClient client,
            DropAiDraftImageLoader imageLoader,
            DailyRequestLimitRepository limitRepository,
            CategoryService categoryService,
            DropAiDraftProperties properties,
            ObjectMapper objectMapper,
            TaskExecutor executor,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.client = client;
        this.imageLoader = imageLoader;
        this.limitRepository = limitRepository;
        this.categoryService = categoryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public DropAiDraftResponse draft(long sellerId, DropAiDraftRequest request) {
        List<DropAiDraftRequest.Image> images = request.images();
        List<String> contentTypes = verifiedContentTypes(images);
        if (!client.available()) {
            throw new BusinessException(DropErrorCode.AI_DRAFT_UNAVAILABLE);
        }
        if (limitRepository.increment(LIMIT_SCOPE, sellerId, LocalDate.now(clock.withZone(KST))) > properties.dailyLimit()) {
            throw new BusinessException(DropErrorCode.AI_DRAFT_LIMIT_EXCEEDED);
        }

        List<DraftImage> loaded = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            try {
                loaded.add(imageLoader.load(images.get(i).imageUrl(), contentTypes.get(i)));
            } catch (IOException e) {
                throw failed("이미지 읽기", e);
            }
        }
        List<CategoryResponse> categories = categoryService.findActiveCategories();
        long startedAt = System.nanoTime();
        Generation generation = call(DropAiDraftPrompt.instruction(categories, images.size()), loaded);
        record(generation, startedAt);

        Draft draft = generation.text() == null ? null : DropAiDraftValidator.validate(
                generation.text(), images.size(), categories, objectMapper).orElse(null);
        if (draft == null || draft.empty()) {
            // 응답 원문은 남기지 않는다(사진 속 글자가 섞일 수 있다)
            log.warn("사진 초안 생성 실패: stage=응답 검증, cause={}", draft == null ? "형식 오류" : "모든 항목 무효");
            count("failed");
            throw new BusinessException(DropErrorCode.AI_DRAFT_FAILED);
        }
        count(draft.complete() ? "generated" : "partial");
        List<ImageAltText> altTexts = draft.altTexts() == null ? null : IntStream.range(0, images.size())
                .mapToObj(i -> new ImageAltText(images.get(i).imageId(), draft.altTexts().get(i)))
                .toList();
        return new DropAiDraftResponse(draft.name(), draft.description(), draft.categoryId(), altTexts);
    }

    // DROP 저장과 같은 오류 형식으로 어긋난 이미지 위치를 알려 준다
    private List<String> verifiedContentTypes(List<DropAiDraftRequest.Image> images) {
        List<String> contentTypes = new ArrayList<>();
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            DropAiDraftRequest.Image image = images.get(i);
            String contentType = imageLoader.contentTypeOf(image.imageId(), image.imageUrl()).orElse(null);
            contentTypes.add(contentType);
            if (contentType == null) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "images[" + i + "].imageUrl", "imageId와 일치하는 공개 이미지 URL이 아닙니다."));
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, fieldErrors);
        }
        return contentTypes;
    }

    private Generation call(String instruction, List<DraftImage> images) {
        CompletableFuture<Generation> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> client.generate(DropAiDraftPrompt.SYSTEM, instruction, images), executor::execute);
        } catch (RejectedExecutionException e) {
            throw failed("호출 대기열 가득 참", e);
        }
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw failed("시간 초과", e);
        } catch (ExecutionException e) {
            throw failed("호출 실패", e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("호출 중단", e);
        }
    }

    // 키·이미지·응답 원문이 로그에 남지 않도록 단계와 예외 종류만 남긴다
    private BusinessException failed(String stage, Throwable cause) {
        log.warn("사진 초안 생성 실패: stage={}, cause={}", stage, cause.getClass().getSimpleName());
        count("failed");
        return new BusinessException(DropErrorCode.AI_DRAFT_FAILED);
    }

    private void record(Generation generation, long startedAt) {
        String model = generation.model() == null || generation.model().isBlank() ? "unknown" : generation.model();
        Timer.builder("grab.drop.ai-draft.duration").tag("model", model).register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
        if (generation.tokens() != null) {
            DistributionSummary.builder("grab.drop.ai-draft.tokens").tag("model", model).register(meterRegistry)
                    .record(generation.tokens());
        }
    }

    private void count(String outcome) {
        meterRegistry.counter("grab.drop.ai-draft", "outcome", outcome.toLowerCase(Locale.ROOT)).increment();
    }
}
