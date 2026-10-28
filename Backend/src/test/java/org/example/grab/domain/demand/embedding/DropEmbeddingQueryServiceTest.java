package org.example.grab.domain.demand.embedding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DropEmbeddingQueryServiceTest {

    private static final String MODEL = "gemini-embedding-001";
    private static final Source SOURCE = new Source("패션", "티셔츠", "코튼 티셔츠", List.of());

    private DropEmbeddingClient client;
    private ValueOperations<String, String> values;
    private DropEmbeddingQueryService service;
    private String key;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(DropEmbeddingClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        given(client.available()).willReturn(true);
        given(client.model()).willReturn(MODEL);
        service = new DropEmbeddingQueryService(client, redisTemplate, new SimpleMeterRegistry());
        key = "demand:embedding:query:" + DropEmbeddingText.sourceHash(MODEL, DropEmbeddingText.build(SOURCE));
    }

    @Test
    @DisplayName("캐시에 없으면 DROP 임베딩과 같은 형식으로 만들어 하루 동안 캐시하고 pgvector 형식으로 돌려준다")
    void embed_createsAndCaches() {
        float[] embedding = new float[DropEmbeddingText.DIMENSIONS];
        embedding[0] = 0.5f;
        given(client.embed(DropEmbeddingText.build(SOURCE))).willReturn(embedding);

        String vector = service.embed(SOURCE).orElseThrow();

        assertThat(vector).startsWith("[0.5,0.0,");
        verify(values).set(key, vector, Duration.ofDays(1));
    }

    @Test
    @DisplayName("같은 텍스트가 캐시에 있으면 API를 부르지 않는다")
    void embed_reusesCache() {
        given(values.get(key)).willReturn("[0.1]");

        assertThat(service.embed(SOURCE)).contains("[0.1]");
        verify(client, never()).embed(anyString());
    }

    @Test
    @DisplayName("상품명·설명이 없거나 모델이 꺼져 있거나 호출이 실패하면 빈 값이고, 캐시 장애는 캐시가 없는 것으로 본다")
    void embed_emptyWhenUnavailable() {
        assertThat(service.embed(new Source("패션", "티셔츠", " ", List.of()))).isEmpty();

        given(values.get(anyString())).willThrow(new RedisConnectionFailureException("down"));
        given(client.embed(anyString())).willThrow(new IllegalStateException("network"));
        assertThat(service.embed(SOURCE)).isEmpty();

        given(client.available()).willReturn(false);
        assertThat(service.embed(SOURCE)).isEmpty();
    }
}
