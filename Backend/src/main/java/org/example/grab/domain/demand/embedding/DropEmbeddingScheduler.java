package org.example.grab.domain.demand.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
    공개 직후 생성에 실패했거나 빠진 DROP, GR-147 이전에 공개된 DROP, 모델을 바꾼 DROP의 임베딩을 채운다(수요 분석 API 6.3).
    임베딩 모델이 꺼져 있으면 아무것도 하지 않는다. fixedDelay라 이전 실행이 끝난 뒤부터 다음 주기를 센다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "grab.demand.embedding.batch.enabled", havingValue = "true", matchIfMissing = true)
public class DropEmbeddingScheduler {

    private final DropEmbeddingService service;

    @Scheduled(fixedDelayString = "${grab.demand.embedding.batch.fixed-delay:10m}")
    public void embedPending() {
        try {
            service.embedPending();
        } catch (Exception e) {
            log.error("DROP 임베딩 배치 실행 실패", e);
        }
    }
}
