package org.example.grab.domain.demand.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.domain.demand.repository.DropEmbeddingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/*
    공개된 DROP의 임베딩을 만들어 저장한다(수요 분석 API 6절). 공개 직후 리스너와 주기 배치가 호출한다.
    실패는 로그·지표로만 남기고 예외를 던지지 않는다. 공개·조회·추천은 임베딩 없이도 카테고리 기준으로 동작하고,
    빠진 DROP은 다음 배치가 다시 만든다.
 */
@Slf4j
@Service
public class DropEmbeddingService {

    private final DropEmbeddingClient client;
    private final DropEmbeddingRepository repository;
    private final DropEmbeddingProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public DropEmbeddingService(
            DropEmbeddingClient client,
            DropEmbeddingRepository repository,
            DropEmbeddingProperties properties,
            MeterRegistry meterRegistry
    ) {
        this(client, repository, properties, meterRegistry, Clock.systemUTC());
    }

    DropEmbeddingService(
            DropEmbeddingClient client,
            DropEmbeddingRepository repository,
            DropEmbeddingProperties properties,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.client = client;
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // 임베딩이 없거나 다른 모델로 만든 공개 DROP을 batchSize만큼 만든다
    public void embedPending() {
        if (!client.available()) {
            return;
        }
        for (long dropId : repository.findTargets(client.model(), properties.batchSize())) {
            embed(dropId);
        }
    }

    public void embed(long dropId) {
        if (!client.available()) {
            return;
        }
        Optional<Source> source = repository.findSource(dropId);
        if (source.isEmpty()) {
            return;
        }
        String text = DropEmbeddingText.build(source.get());
        String sourceHash = DropEmbeddingText.sourceHash(client.model(), text);
        if (repository.findSourceHash(dropId).filter(sourceHash::equals).isPresent()) {
            count("skipped");
            return;
        }
        try {
            float[] embedding = client.embed(text);
            if (embedding.length != DropEmbeddingText.DIMENSIONS) {
                throw new IllegalStateException("임베딩 차원이 " + DropEmbeddingText.DIMENSIONS + "이 아닙니다: "
                        + embedding.length);
            }
            repository.save(dropId, embedding, client.model(), sourceHash, OffsetDateTime.now(clock));
            count("created");
        } catch (RuntimeException e) {
            // 상품 텍스트·키가 로그에 남지 않도록 예외 종류만 남긴다
            log.warn("DROP 임베딩 생성 실패: dropId={}, cause={}", dropId, e.getClass().getSimpleName());
            count("failed");
        }
    }

    private void count(String outcome) {
        meterRegistry.counter("grab.demand.embedding", "outcome", outcome).increment();
    }
}
