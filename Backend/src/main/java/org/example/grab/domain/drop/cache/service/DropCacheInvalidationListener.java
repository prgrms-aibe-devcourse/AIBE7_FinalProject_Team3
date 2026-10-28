package org.example.grab.domain.drop.cache.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.stream.model.StockChangedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재고·판매·상태 변경이 커밋된 뒤 공개 DROP 캐시를 지운다(GR-183).
 * SSE 알림(StockChangeListener)보다 먼저 실행해, 알림을 받은 화면의 재조회가 지워진 캐시를 보게 한다.
 * 지우기 실패로 이미 커밋된 주문 응답을 바꾸지 않는다. TTL이 남은 지연의 상한이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DropCacheInvalidationListener {

    private final PublicDropCache cache;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(StockChangedEvent event) {
        try {
            cache.evict(event.dropId());
        } catch (RuntimeException exception) {
            log.warn("커밋 후 공개 DROP 캐시 삭제 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
