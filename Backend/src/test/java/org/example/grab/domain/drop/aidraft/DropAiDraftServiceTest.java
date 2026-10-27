package org.example.grab.domain.drop.aidraft;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.drop.aidraft.DropAiDraftClient.DraftImage;
import org.example.grab.domain.drop.aidraft.DropAiDraftClient.Generation;
import org.example.grab.domain.drop.aidraft.DropAiDraftResponse.ImageAltText;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DropAiDraftServiceTest {

    private static final long SELLER_ID = 3L;
    // 2026-10-26 23:30 KST(UTC로는 14:30) → 한도는 KST 날짜로 센다
    private static final Instant NOW = Instant.parse("2026-10-26T14:30:00Z");
    private static final UUID FIRST = UUID.fromString("7c0d3a1e-1b2f-4c55-9d1e-2a4b6c8d0e1f");
    private static final UUID SECOND = UUID.fromString("8d1e4b2f-2c3a-4d66-8e2f-3b5c7d9e1f20");
    private static final List<CategoryResponse> CATEGORIES = List.of(new CategoryResponse(1L, "FASHION", "패션"));
    private static final String COMPLETE = """
            {"name": "블랙 니트 가디건", "description": "도톰한 블랙 니트 가디건입니다.",
             "categoryCode": "FASHION", "altTexts": ["가디건 정면", "가디건 뒷면"]}
            """;

    private DropAiDraftClient client;
    private DropAiDraftImageLoader imageLoader;
    private DailyRequestLimitRepository limitRepository;
    private CategoryService categoryService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(DropAiDraftClient.class);
        imageLoader = mock(DropAiDraftImageLoader.class);
        limitRepository = mock(DailyRequestLimitRepository.class);
        categoryService = mock(CategoryService.class);
        meterRegistry = new SimpleMeterRegistry();
        given(client.available()).willReturn(true);
        given(imageLoader.contentTypeOf(any(), anyString())).willReturn(Optional.of("image/jpeg"));
        given(imageLoader.load(anyString(), anyString())).willReturn(new DraftImage(new byte[]{1}, "image/jpeg"));
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(1L);
        given(categoryService.findActiveCategories()).willReturn(CATEGORIES);
    }

    @Test
    @DisplayName("업로드 이미지로 만든 초안을 카테고리 ID·이미지별 대체 설명으로 돌려주고, 한도는 KST 날짜로 센다")
    void draft_returnsSuggestion() {
        given(client.generate(eq(DropAiDraftPrompt.SYSTEM), contains("FASHION: 패션"), anyList()))
                .willReturn(new Generation(COMPLETE, "gemini-2.5-flash", 900));

        DropAiDraftResponse response = service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(2));

        assertThat(response).isEqualTo(new DropAiDraftResponse("블랙 니트 가디건", "도톰한 블랙 니트 가디건입니다.", 1L,
                List.of(new ImageAltText(FIRST, "가디건 정면"), new ImageAltText(SECOND, "가디건 뒷면"))));
        verify(limitRepository).increment(DropAiDraftService.LIMIT_SCOPE, SELLER_ID, LocalDate.of(2026, 10, 26));
        assertThat(meterRegistry.counter("grab.drop.ai-draft", "outcome", "generated").count()).isEqualTo(1);
        assertThat(meterRegistry.summary("grab.drop.ai-draft.tokens", "model", "gemini-2.5-flash").totalAmount())
                .isEqualTo(900);
    }

    @Test
    @DisplayName("일부 항목만 통과하면 나머지는 null로 두고 partial로 센다")
    void draft_returnsPartialSuggestion() {
        given(client.generate(anyString(), anyString(), anyList())).willReturn(new Generation(
                "{\"name\": \"가디건\", \"categoryCode\": \"UNKNOWN\", \"altTexts\": []}", "m", null));

        DropAiDraftResponse response = service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(1));

        assertThat(response).isEqualTo(new DropAiDraftResponse("가디건", null, null, null));
        assertThat(meterRegistry.counter("grab.drop.ai-draft", "outcome", "partial").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("imageId의 공개 URL이 아닌 이미지는 위치와 함께 VALIDATION_FAILED로 거부하고 한도·다운로드를 쓰지 않는다")
    void draft_rejectsForeignImageUrl() throws IOException {
        given(imageLoader.contentTypeOf(SECOND, url(SECOND))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(2)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("images[1].imageUrl");
                });
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
        verify(imageLoader, never()).load(anyString(), anyString());
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 AI_DRAFT_UNAVAILABLE, 하루 한도를 넘으면 AI_DRAFT_LIMIT_EXCEEDED로 거부한다")
    void draft_rejectsUnavailableOrOverLimit() throws IOException {
        given(client.available()).willReturn(false);
        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(1)))
                .extracting("errorCode").isEqualTo(DropErrorCode.AI_DRAFT_UNAVAILABLE);
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());

        given(client.available()).willReturn(true);
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(21L);
        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(1)))
                .extracting("errorCode").isEqualTo(DropErrorCode.AI_DRAFT_LIMIT_EXCEEDED);
        verify(imageLoader, never()).load(anyString(), anyString());
    }

    @Test
    @DisplayName("이미지를 읽지 못하거나 응답이 JSON이 아니면 AI_DRAFT_FAILED로 응답한다")
    void draft_failsOnImageOrResponseError() throws IOException {
        given(imageLoader.load(anyString(), anyString())).willThrow(new IOException("404"));
        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(1)))
                .extracting("errorCode").isEqualTo(DropErrorCode.AI_DRAFT_FAILED);

        willReturn(new DraftImage(new byte[]{1}, "image/jpeg")).given(imageLoader).load(anyString(), anyString());
        given(client.generate(anyString(), anyString(), anyList())).willReturn(new Generation("초안입니다", "m", 1));
        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(20)).draft(SELLER_ID, request(1)))
                .extracting("errorCode").isEqualTo(DropErrorCode.AI_DRAFT_FAILED);
        assertThat(meterRegistry.counter("grab.drop.ai-draft", "outcome", "failed").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("시간 제한을 넘기면 응답을 기다리지 않고 AI_DRAFT_FAILED로 응답한다")
    void draft_failsOnTimeout() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        given(client.generate(anyString(), anyString(), anyList())).willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return new Generation(COMPLETE, "m", 1);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> service(executor::execute, Duration.ofMillis(100)).draft(SELLER_ID, request(2)))
                    .extracting("errorCode").isEqualTo(DropErrorCode.AI_DRAFT_FAILED);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private DropAiDraftService service(TaskExecutor executor, Duration timeout) {
        return new DropAiDraftService(client, imageLoader, limitRepository, categoryService,
                new DropAiDraftProperties(timeout, timeout.dividedBy(2), Duration.ofSeconds(5), 20, 1024, 0),
                JsonMapper.builder().build(), executor, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DropAiDraftRequest request(int count) {
        return new DropAiDraftRequest(List.of(FIRST, SECOND).subList(0, count).stream()
                .map(id -> new DropAiDraftRequest.Image(id, url(id))).toList());
    }

    private static String url(UUID imageId) {
        return "https://project.supabase.co/storage/v1/object/public/drop-images/images/" + imageId + ".jpg";
    }
}
