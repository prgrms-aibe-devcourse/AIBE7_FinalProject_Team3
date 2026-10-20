package org.example.grab.domain.drop.stream.transport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.stream.model.StockChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockChangeListener {
    private final StockStreamHub hub;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(StockChangedEvent event) {
        try {
            // 유한 버퍼에만 합친다. DB 조회와 소켓 송신은 기존 전송 실행기가 담당한다.
            hub.publish(event.dropId(), event.changes());
        } catch (RuntimeException exception) {
            // 이미 커밋된 주문 응답을 알림 실패로 바꾸지 않는다. 주기 조회가 유실을 복구한다.
            log.warn("커밋 후 재고 변경 알림 전달 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
