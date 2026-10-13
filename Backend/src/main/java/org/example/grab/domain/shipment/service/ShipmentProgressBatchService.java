package org.example.grab.domain.shipment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.shipment.repository.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 배송 단계 Mock 자동 진행 배치 한 번을 별도 트랜잭션으로 처리한다(GR-86).
 * ShipmentProgressService와 별도 빈으로 분리해 프록시를 거치게 함으로써,
 * self-invocation으로 @Transactional이 적용되지 않는 문제를 막는다(DropTransitionBatchService와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class ShipmentProgressBatchService {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public int advanceStageBatch(OffsetDateTime now, OffsetDateTime cutoff, int batchSize) {
        return shipmentRepository.advanceStageBatch(now, cutoff, batchSize);
    }

    @Transactional
    public int completeDeliveryBatch(OffsetDateTime now, OffsetDateTime cutoff, int batchSize) {
        List<Long> orderIds = shipmentRepository.lockDeliveryCompletionTargets(cutoff, batchSize);
        if (orderIds.isEmpty()) {
            return 0;
        }
        orderRepository.markDelivered(orderIds, now);
        shipmentRepository.markDelivered(orderIds, now);
        return orderIds.size();
    }
}
