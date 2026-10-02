package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * DROP 상태 자동 전환을 조율한다(GR-18).
 * 구매 판정은 서버 시간 기준이라 이 전환은 저장 상태·조회·통계·후속 이벤트를 동기화하는 역할만 담당한다.
 * 시작 배치를 모두 처리한 뒤 종료 배치를 처리해, 판매 기간을 통째로 놓친 DROP도 같은 실행에서
 * WISH → GRAB → ENDED 순서를 거치게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DropTransitionService {

    private static final int BATCH_SIZE = 500;

    private final DropTransitionBatchService batchService;

    /** 한 번의 스케줄러 실행. 모든 배치가 같은 now를 공유한다. 전환 건수(GRAB·ENDED)를 반환한다. */
    public long transition(OffsetDateTime now) {
        long started = drain(now, true);
        long ended = drain(now, false);
        if (started + ended > 0) {
            log.info("DROP 상태 전환: GRAB {}건, ENDED {}건", started, ended);
        }
        return started + ended;
    }

    /*
     * 한 종류의 배치를 반복 실행한다. UPDATE 반환 건수가 BATCH_SIZE일 때만 다음 배치를 시도한다.
     * 남은 대상 확인용 COUNT 쿼리는 쓰지 않는다. 다른 트랜잭션이 잠근 행은 조회에서 빠지므로
     * COUNT 기준 반복은 무한 루프를 만들 수 있다.
     */
    private long drain(OffsetDateTime now, boolean start) {
        long total = 0;
        while (true) {
            int updated = start
                    ? batchService.startGrabBatch(now, BATCH_SIZE)
                    : batchService.endGrabBatch(now, BATCH_SIZE);
            total += updated;
            if (updated < BATCH_SIZE) {
                return total;
            }
        }
    }
}
