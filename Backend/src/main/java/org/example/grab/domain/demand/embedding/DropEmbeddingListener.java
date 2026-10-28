package org.example.grab.domain.demand.embedding;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.event.DropPublishedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
    DROP 공개가 커밋된 뒤 임베딩을 전용 풀에서 만든다(수요 분석 API 6.3). 공개 응답은 임베딩을 기다리지 않는다.
    풀이 가득 차거나 서버가 곧 종료돼 빠진 DROP은 주기 배치(DropEmbeddingScheduler)가 다시 만든다.
 */
@Slf4j
@Component
public class DropEmbeddingListener {

    private final DropEmbeddingService service;
    private final TaskExecutor executor;

    public DropEmbeddingListener(
            DropEmbeddingService service,
            @Qualifier(DropEmbeddingConfig.DROP_EMBEDDING_TASK_EXECUTOR) TaskExecutor executor
    ) {
        this.service = service;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDropPublished(DropPublishedEvent event) {
        try {
            executor.execute(() -> service.embed(event.dropId()));
        } catch (TaskRejectedException e) {
            log.warn("DROP 임베딩 생성 대기열이 가득 차 배치로 미룸: dropId={}", event.dropId());
        }
    }
}
