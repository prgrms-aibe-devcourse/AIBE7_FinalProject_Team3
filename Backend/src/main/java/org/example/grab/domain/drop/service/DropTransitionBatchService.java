package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.repository.DropRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * DROP 상태 전환 배치 한 번을 별도 트랜잭션으로 처리한다(GR-18).
 * DropTransitionService와 별도 빈으로 분리해 프록시를 거치게 함으로써,
 * self-invocation으로 @Transactional이 적용되지 않는 문제를 막는다.
 * 호출마다 커밋해 행 잠금을 짧게 유지하고, 변경 건수를 반환해 호출자가 다음 배치 여부를 판단한다.
 */
@Service
@RequiredArgsConstructor
public class DropTransitionBatchService {

    private final DropRepository dropRepository;

    @Transactional
    public int startGrabBatch(OffsetDateTime now, int batchSize) {
        return dropRepository.startGrabBatch(now, batchSize);
    }

    @Transactional
    public int endGrabBatch(OffsetDateTime now, int batchSize) {
        return dropRepository.endGrabBatch(now, batchSize);
    }
}
