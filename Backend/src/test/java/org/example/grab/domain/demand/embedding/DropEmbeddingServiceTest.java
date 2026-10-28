package org.example.grab.domain.demand.embedding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.domain.demand.repository.DropEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DropEmbeddingServiceTest {

    private static final long DROP_ID = 100L;
    private static final String MODEL = "gemini-embedding-001";
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static final Source SOURCE = new Source("패션", "오버핏 티셔츠", "부드러운 코튼 티셔츠", List.of());

    private DropEmbeddingClient client;
    private DropEmbeddingRepository repository;
    private SimpleMeterRegistry meterRegistry;
    private DropEmbeddingService service;
    private String sourceHash;

    @BeforeEach
    void setUp() {
        client = mock(DropEmbeddingClient.class);
        repository = mock(DropEmbeddingRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        given(client.available()).willReturn(true);
        given(client.model()).willReturn(MODEL);
        given(repository.findSource(DROP_ID)).willReturn(Optional.of(SOURCE));
        given(repository.findSourceHash(DROP_ID)).willReturn(Optional.empty());
        sourceHash = DropEmbeddingText.sourceHash(MODEL, DropEmbeddingText.build(SOURCE));
        service = new DropEmbeddingService(client, repository, new DropEmbeddingProperties(Duration.ofSeconds(10), 20),
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("공개 카탈로그 텍스트로 임베딩을 만들어 모델·입력 해시와 함께 저장한다")
    void embed_savesEmbedding() {
        float[] embedding = new float[DropEmbeddingText.DIMENSIONS];
        given(client.embed(DropEmbeddingText.build(SOURCE))).willReturn(embedding);

        service.embed(DROP_ID);

        verify(repository).save(DROP_ID, embedding, MODEL, sourceHash, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(count("created")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 모델·입력의 임베딩이 이미 있으면 다시 만들지 않는다")
    void embed_skipsSameSource() {
        given(repository.findSourceHash(DROP_ID)).willReturn(Optional.of(sourceHash));

        service.embed(DROP_ID);

        verify(client, never()).embed(anyString());
        assertThat(count("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("호출이 실패하거나 차원이 다르면 저장하지 않고 예외도 던지지 않는다")
    void embed_failsWithoutSaving() {
        given(client.embed(anyString())).willThrow(new IllegalStateException("network"));
        service.embed(DROP_ID);

        willReturn(new float[3]).given(client).embed(anyString());
        service.embed(DROP_ID);

        verify(repository, never()).save(anyLong(), any(), anyString(), anyString(), any());
        assertThat(count("failed")).isEqualTo(2);
    }

    @Test
    @DisplayName("공개되지 않았거나 없는 DROP과, 임베딩 모델이 꺼진 경우에는 아무것도 하지 않는다")
    void embed_ignoresUnpublishedOrDisabled() {
        given(repository.findSource(DROP_ID)).willReturn(Optional.empty());
        service.embed(DROP_ID);

        given(client.available()).willReturn(false);
        service.embedPending();

        verify(client, never()).embed(anyString());
        verify(repository, never()).findTargets(anyString(), eq(20));
    }

    @Test
    @DisplayName("배치는 현재 모델 기준 대상 DROP을 batchSize만큼 골라 하나씩 만든다")
    void embedPending_embedsTargets() {
        given(repository.findTargets(MODEL, 20)).willReturn(List.of(DROP_ID));
        given(client.embed(anyString())).willReturn(new float[DropEmbeddingText.DIMENSIONS]);

        service.embedPending();

        verify(repository).save(eq(DROP_ID), any(), eq(MODEL), eq(sourceHash), any());
    }

    private double count(String outcome) {
        return meterRegistry.counter("grab.demand.embedding", "outcome", outcome).count();
    }
}
