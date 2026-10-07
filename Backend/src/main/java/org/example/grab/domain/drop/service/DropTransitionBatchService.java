package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.event.DropGrabStartedEvent;
import org.example.grab.domain.drop.event.DropSaleStartingSoonEvent;
import org.example.grab.domain.drop.repository.DropRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * DROP 상태 전환·알림 선점 배치 한 번을 별도 트랜잭션으로 처리한다(GR-18, GR-69).
 * DropTransitionService와 별도 빈으로 분리해 프록시를 거치게 함으로써,
 * self-invocation으로 @Transactional이 적용되지 않는 문제를 막는다.
 * 호출마다 커밋해 행 잠금을 짧게 유지하고, 처리 결과를 반환해 호출자가 다음 배치 여부를 판단한다.
 */
@Service
@RequiredArgsConstructor
public class DropTransitionBatchService {

    // drop_notifications.type. CHECK 제약(ck_drop_notifications_type)에 있는 값만 쓸 수 있다
    private static final String PRESALE_NOTICE_TYPE = "PRESALE_10M";

    private final DropRepository dropRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 판매 시작 임박 알림 배치 한 번. 발송 기록 선점에 성공한 DROP ID를 반환한다(GR-69).
     * 선점과 ID 확보가 한 문장이라, 반환된 ID는 이 인스턴스만 받은 것이다.
     */
    @Transactional
    public List<Long> presaleNoticeBatch(OffsetDateTime now, int batchSize) {
        List<Long> dropIds = dropRepository.claimNoticeBatch(
                PRESALE_NOTICE_TYPE, now, now.plus(DropSaleStartingSoonEvent.LEAD), batchSize);
        if (!dropIds.isEmpty()) {
            eventPublisher.publishEvent(new DropSaleStartingSoonEvent(dropIds));
        }
        return dropIds;
    }

    /**
     * 시작 배치 한 번. 전환된 DROP ID를 반환한다.
     * 전환이 있으면 같은 트랜잭션에서 이벤트를 발행한다. 커밋 전에는 수신 측이 동작하지 않으므로,
     * 롤백된 전환에 대해 후속 처리(GR-69 판매 시작 메일)가 일어나지 않는다.
     */
    @Transactional
    public List<Long> startGrabBatch(OffsetDateTime now, int batchSize) {
        List<Long> startedDropIds = dropRepository.startGrabBatch(now, batchSize);
        if (!startedDropIds.isEmpty()) {
            eventPublisher.publishEvent(new DropGrabStartedEvent(startedDropIds));
        }
        return startedDropIds;
    }

    @Transactional
    public int endGrabBatch(OffsetDateTime now, int batchSize) {
        return dropRepository.endGrabBatch(now, batchSize);
    }
}
