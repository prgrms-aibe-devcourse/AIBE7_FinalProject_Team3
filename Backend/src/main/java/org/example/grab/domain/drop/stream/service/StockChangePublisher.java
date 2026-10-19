package org.example.grab.domain.drop.stream.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.model.StockChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockChangePublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publish(long dropId, StockChange... changes) {
        // 트랜잭션 밖의 호출은 커밋된 도메인 사실임을 보장할 수 없다.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try {
            eventPublisher.publishEvent(new StockChangedEvent(dropId, Set.of(changes)));
        } catch (RuntimeException exception) {
            // 변경 알림은 최선 노력이다. 알림 등록 실패로 주문·결제 트랜잭션을 되돌리지 않는다.
            log.warn("재고 변경 알림 등록 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
