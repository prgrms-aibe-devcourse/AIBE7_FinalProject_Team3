package org.example.grab.domain.shipment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.IntUnaryOperator;

/**
 * 실제 택배사 연동 없이, SHIPPED 이후 배송 세부 단계를 시간 경과로 자동 진행시킨다(GR-86).
 * 판매자가 수동으로 처리하는 PAID→PREPARING→SHIPPED 구간은 건드리지 않는다.
 * DropTransitionService와 같은 구조: 실제 갱신은 배치 서비스에 맡기고, 여기서는 배치를 반복 호출한다.
 */
@Slf4j
@Service
public class ShipmentProgressService {

    private static final int BATCH_SIZE = 500;

    private final ShipmentProgressBatchService batchService;
    private final Duration stageInterval;

    public ShipmentProgressService(
            ShipmentProgressBatchService batchService,
            @Value("${grab.mock-delivery.stage-interval:30s}") Duration stageInterval) {
        if (stageInterval.isZero() || stageInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "grab.mock-delivery.stage-interval은 양수여야 합니다: " + stageInterval);
        }
        this.batchService = batchService;
        this.stageInterval = stageInterval;
    }

    /** 한 번의 스케줄러 실행. 단계 전진과 배송완료 전환 건수의 합을 반환한다. */
    public long progress(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(stageInterval);
        long advanced = drain(batchSize -> batchService.advanceStageBatch(now, cutoff, batchSize));
        long completed = drain(batchSize -> batchService.completeDeliveryBatch(now, cutoff, batchSize));
        if (advanced + completed > 0) {
            log.info("배송 Mock 진행: 단계 전진 {}건, 배송완료 {}건", advanced, completed);
        }
        return advanced + completed;
    }

    private long drain(IntUnaryOperator batch) {
        long total = 0;
        while (true) {
            int processed = batch.applyAsInt(BATCH_SIZE);
            total += processed;
            if (processed < BATCH_SIZE) {
                return total;
            }
        }
    }
}
