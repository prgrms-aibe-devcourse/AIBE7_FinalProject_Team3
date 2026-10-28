package org.example.grab.domain.demand.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.event.DropGrabStartedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
    DROP 판매 시작(WISH → GRAB)이 커밋된 뒤 그 시점의 수요 분석을 기록한다(DEMAND.md 2.3, DEMAND-005).
    DROP마다 별도 트랜잭션(DemandAnalysisSnapshotRecorder)에서 기록하고, 한 DROP의 실패가 나머지 기록을 막지 않게 한다.
    예외를 트랜잭션 바깥에서 받는 이유는 공개 시점 기록(StockRecommendationSnapshotListener)과 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandAnalysisSnapshotListener {

    private final DemandAnalysisSnapshotRecorder recorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDropGrabStarted(DropGrabStartedEvent event) {
        for (Long dropId : event.dropIds()) {
            try {
                recorder.record(dropId);
            } catch (RuntimeException e) {
                // 평가용 기록이라 재시도하지 않는다. 수요 수치가 로그에 남지 않게 예외 종류만 남긴다
                log.warn("판매 시작 시점 수요 분석 기록 실패: dropId={}, cause={}", dropId, e.getClass().getSimpleName());
            }
        }
    }
}
