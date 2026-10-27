package org.example.grab.domain.demand.explanation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.example.grab.domain.demand.repository.DemandExplanationRepository;
import org.example.grab.domain.demand.repository.DemandExplanationRepository.StoredExplanation;
import org.example.grab.domain.demand.service.DemandAnalysisService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.ratelimit.DailyRequestLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DemandExplanationServiceTest {

    private static final long SELLER_ID = 3L;
    private static final long DROP_ID = 100L;
    // 2026-10-27 23:30 KST(UTC로는 14:30) → 한도는 KST 날짜로 센다
    private static final Instant NOW = Instant.parse("2026-10-27T14:30:00Z");
    private static final DemandCalculateRequest REQUEST =
            new DemandCalculateRequest(ConversionBasis.OWN, null, new BigDecimal("0.05"));

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final DemandAnalysisResponse analysis = DemandExplanationPromptTest.analysis();
    private DemandAnalysisService analysisService;
    private DemandExplanationClient client;
    private DemandExplanationRepository repository;
    private DailyRequestLimitRepository limitRepository;
    private SimpleMeterRegistry meterRegistry;
    private String input;
    private String inputHash;

    @BeforeEach
    void setUp() {
        analysisService = mock(DemandAnalysisService.class);
        client = mock(DemandExplanationClient.class);
        repository = mock(DemandExplanationRepository.class);
        limitRepository = mock(DailyRequestLimitRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        given(analysisService.analyze(SELLER_ID, DROP_ID, REQUEST)).willReturn(analysis);
        given(client.available()).willReturn(true);
        given(repository.find(anyLong(), anyString())).willReturn(Optional.empty());
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(1L);
        input = DemandExplanationPrompt.input(analysis, objectMapper);
        inputHash = DemandExplanationService.inputHash(input);
    }

    @Test
    @DisplayName("판매자가 고른 가정의 분석으로 설명을 만들고, 검증을 통과하면 저장해 GENERATED로 응답한다")
    void explain_generatesForSellerAssumptions() {
        // given: 설명의 숫자(126·139)는 입력의 예상 판매량·준비 권장 수량과 같다
        given(client.generate(DemandExplanationPrompt.SYSTEM, input)).willReturn(new Generation(
                "{\"summary\":\"예상 판매량은 126개, 준비 권장 수량은 139개입니다.\"}", "gemini-2.5-flash", 812));

        // when
        DemandExplanationResponse response = service(Runnable::run, Duration.ofSeconds(10))
                .explain(SELLER_ID, DROP_ID, REQUEST);

        // then
        assertThat(response.status()).isEqualTo(ExplanationStatus.GENERATED);
        assertThat(response.explanation().summary()).startsWith("예상 판매량은 126개");
        assertThat(response.generatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(repository).save(eq(DROP_ID), eq(inputHash), eq("GENERATED"), contains("\"summary\""),
                eq("gemini-2.5-flash"), eq(DemandExplanationPrompt.VERSION), eq(812), any());
        verify(limitRepository).increment(DemandExplanationService.LIMIT_SCOPE, SELLER_ID, LocalDate.of(2026, 10, 27));
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "generated").count()).isEqualTo(1);
        assertThat(meterRegistry.summary("grab.demand.explanation.tokens", "model", "gemini-2.5-flash")
                .totalAmount()).isEqualTo(812);
    }

    @Test
    @DisplayName("같은 입력의 설명이 저장돼 있으면 LLM을 부르지 않고 한도도 세지 않는다")
    void explain_reusesStoredExplanation() {
        OffsetDateTime storedAt = OffsetDateTime.parse("2026-10-27T10:00:00+09:00");
        given(repository.find(DROP_ID, inputHash)).willReturn(Optional.of(new StoredExplanation(
                "GENERATED", "{\"summary\":\"저장된 설명\",\"reasons\":[],\"risks\":[],\"dataGaps\":[],\"notRecommended\":[]}",
                storedAt)));

        DemandExplanationResponse response = service(Runnable::run, Duration.ofSeconds(10))
                .explain(SELLER_ID, DROP_ID, REQUEST);

        assertThat(response.status()).isEqualTo(ExplanationStatus.GENERATED);
        assertThat(response.explanation().summary()).isEqualTo("저장된 설명");
        assertThat(response.generatedAt()).isEqualTo(storedAt);
        verify(client, never()).generate(anyString(), anyString());
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "reused").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("입력에 없는 숫자가 있는 설명은 원문 없이 DISCARDED로 저장하고 응답한다")
    void explain_discardsMismatchedNumbers() {
        given(client.generate(anyString(), anyString())).willReturn(
                new Generation("{\"summary\":\"예상 판매량은 130개입니다.\"}", "gemini-2.5-flash", 500));

        DemandExplanationResponse response = service(Runnable::run, Duration.ofSeconds(10))
                .explain(SELLER_ID, DROP_ID, REQUEST);

        assertThat(response.status()).isEqualTo(ExplanationStatus.DISCARDED);
        assertThat(response.explanation()).isNull();
        verify(repository).save(eq(DROP_ID), eq(inputHash), eq("DISCARDED"), isNull(), eq("gemini-2.5-flash"),
                eq(DemandExplanationPrompt.VERSION), eq(500), any());
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 LLM·한도·저장 없이 DISABLED로 응답한다")
    void explain_disabledWithoutModel() {
        given(client.available()).willReturn(false);

        DemandExplanationResponse response = service(Runnable::run, Duration.ofSeconds(10))
                .explain(SELLER_ID, DROP_ID, REQUEST);

        assertThat(response).isEqualTo(DemandExplanationResponse.of(ExplanationStatus.DISABLED));
        verify(limitRepository, never()).increment(anyString(), anyLong(), any());
        verify(repository, never()).find(anyLong(), anyString());
    }

    @Test
    @DisplayName("하루 한도를 넘으면 LLM을 부르지 않고 DEMAND_EXPLANATION_LIMIT_EXCEEDED로 거부한다")
    void explain_rejectsOverDailyLimit() {
        given(limitRepository.increment(anyString(), anyLong(), any())).willReturn(31L);

        assertThatThrownBy(() -> service(Runnable::run, Duration.ofSeconds(10)).explain(SELLER_ID, DROP_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED);
        verify(client, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("호출 실패·대기열 가득 참은 저장하지 않고 FAILED로 응답한다")
    void explain_failsWithoutSaving() {
        given(client.generate(anyString(), anyString())).willThrow(new IllegalStateException("network"));
        assertThat(service(Runnable::run, Duration.ofSeconds(10)).explain(SELLER_ID, DROP_ID, REQUEST).status())
                .isEqualTo(ExplanationStatus.FAILED);

        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("가득 참");
        };
        assertThat(service(rejecting, Duration.ofSeconds(10)).explain(SELLER_ID, DROP_ID, REQUEST).status())
                .isEqualTo(ExplanationStatus.FAILED);
        verify(repository, never()).save(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(meterRegistry.counter("grab.demand.explanation", "outcome", "failed").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("시간 제한을 넘기면 응답을 기다리지 않고 FAILED로 응답한다")
    void explain_failsOnTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        given(client.generate(anyString(), anyString())).willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return new Generation("{\"summary\":\"늦은 설명\"}", "m", 1);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(service(executor::execute, Duration.ofMillis(100)).explain(SELLER_ID, DROP_ID, REQUEST)
                    .status()).isEqualTo(ExplanationStatus.FAILED);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("입력 해시는 프롬프트 버전과 입력이 같으면 같고, 입력이 다르면 다르다")
    void inputHash_dependsOnInput() {
        assertThat(DemandExplanationService.inputHash("{\"a\":1}"))
                .isEqualTo(DemandExplanationService.inputHash("{\"a\":1}"))
                .isNotEqualTo(DemandExplanationService.inputHash("{\"a\":2}"))
                .hasSize(64);
    }

    private DemandExplanationService service(TaskExecutor executor, Duration timeout) {
        return new DemandExplanationService(analysisService, client, repository, limitRepository,
                new DemandExplanationProperties(timeout, timeout.dividedBy(2), 1024, 0, 30), objectMapper, executor, meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
