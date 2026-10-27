package org.example.grab.domain.demand.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.event.DropPublishedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
    DROP 공개가 커밋된 뒤 공개 시점 재고 추천을 기록한다(DEMAND.md 5.6, DEMAND-012).
    기록은 별도 트랜잭션(StockRecommendationSnapshotRecorder)에서 하고 예외는 그 바깥에서 받는다.
    트랜잭션 안에서 받으면 커밋 단계의 실패가 다시 던져져, 이미 끝난 공개 요청이 실패한 것처럼 보일 수 있기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRecommendationSnapshotListener {

    private final StockRecommendationSnapshotRecorder recorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDropPublished(DropPublishedEvent event) {
        try {
            recorder.record(event.dropId());
        } catch (RuntimeException e) {
            // 평가용 기록이라 재시도하지 않는다. 판매 수치가 로그에 남지 않게 예외 종류만 남긴다
            log.warn("공개 시점 재고 추천 기록 실패: dropId={}, cause={}", event.dropId(), e.getClass().getSimpleName());
        }
    }
}
