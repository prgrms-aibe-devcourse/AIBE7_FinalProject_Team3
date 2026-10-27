package org.example.grab.domain.demand.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/*
    생성 단계 비슷한 DROP 실적(DEMAND.md 7.5)에 쓰는 요청 임베딩. 저장 전 폼 입력을
    DROP 임베딩과 같은 형식(6.1)·같은 모델로 만들어 pgvector 입력 형식으로 돌려준다.
    같은 텍스트면 Redis 캐시(하루)를 다시 쓴다. 캐시는 다시 만들 수 있는 값이라 비워도 결과에는 영향이 없다.
    만들 수 없으면(꺼짐, 상품명·설명 없음, 실패·시간 초과) 빈 값을 돌려주고, 호출 측은 카테고리 기준으로 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DropEmbeddingQueryService {

    private static final String KEY_PREFIX = "demand:embedding:query:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);

    private final DropEmbeddingClient client;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public Optional<String> embed(Source source) {
        if (!usable(source)) {
            return Optional.empty();
        }
        String text = DropEmbeddingText.build(source);
        String key = key(text);
        Optional<String> cached = cached(key);
        if (cached.isPresent()) {
            count("cached");
            return cached;
        }
        try {
            float[] embedding = client.embed(text);
            if (embedding.length != DropEmbeddingText.DIMENSIONS) {
                throw new IllegalStateException("임베딩 차원이 " + DropEmbeddingText.DIMENSIONS + "이 아닙니다: "
                        + embedding.length);
            }
            String vector = DropEmbeddingText.toVectorLiteral(embedding);
            cache(key, vector);
            count("created");
            return Optional.of(vector);
        } catch (RuntimeException e) {
            // 판매자 입력·키가 로그에 남지 않도록 예외 종류만 남긴다
            log.warn("요청 임베딩 생성 실패: cause={}", e.getClass().getSimpleName());
            count("failed");
            return Optional.empty();
        }
    }

    private boolean usable(Source source) {
        return client.available() && StringUtils.hasText(source.name()) && StringUtils.hasText(source.description());
    }

    private String key(String text) {
        return KEY_PREFIX + DropEmbeddingText.sourceHash(client.model(), text);
    }

    // Redis 장애는 캐시가 없는 것과 같게 본다
    private Optional<String> cached(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("요청 임베딩 캐시 조회 실패: cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void cache(String key, String vector) {
        try {
            redisTemplate.opsForValue().set(key, vector, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("요청 임베딩 캐시 저장 실패: cause={}", e.getClass().getSimpleName());
        }
    }

    private void count(String outcome) {
        meterRegistry.counter("grab.demand.embedding.query", "outcome", outcome).increment();
    }
}
