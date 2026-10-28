package org.example.grab.domain.demand.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/*
    DROP 임베딩 생성 정책(수요 분석 API 6절). 모델·API 키는 spring.ai.* 설정이 맡는다.
    timeout은 임베딩 API의 HTTP 시간 제한이고, batchSize는 주기 배치가 한 번에 만드는 최대 DROP 수다.
 */
@ConfigurationProperties("grab.demand.embedding")
public record DropEmbeddingProperties(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("20") int batchSize
) {
    public DropEmbeddingProperties {
        if (timeout.isNegative() || timeout.isZero() || batchSize < 1) {
            throw new IllegalArgumentException("grab.demand.embedding 설정값이 허용 범위를 벗어났습니다.");
        }
    }
}
