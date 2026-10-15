package org.example.grab.domain.drop.stream.service;

import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.model.StockChangedEvent;
import org.example.grab.domain.drop.stream.transport.StockChangeListener;
import org.example.grab.domain.drop.stream.transport.StockStreamHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class StockChangePublisherTest {
    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("트랜잭션 밖의 알림은 발행하지 않는다")
    void ignoresNonTransactionalCalls() {
        // given
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        StockChangePublisher publisher = new StockChangePublisher(events);

        // when
        publisher.publish(1, StockChange.STOCK);

        // then
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("이벤트 등록 실패는 도메인 트랜잭션으로 전파하지 않는다")
    void isolatesRegistrationFailure() {
        // given
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("등록 실패")).when(events).publishEvent(any(Object.class));
        StockChangePublisher publisher = new StockChangePublisher(events);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // when / then
        assertThatCode(() -> publisher.publish(1, StockChange.STOCK)).doesNotThrowAnyException();
        verify(events).publishEvent(new StockChangedEvent(1, Set.of(StockChange.STOCK)));
    }

    @Test
    @DisplayName("커밋 후 전달 실패는 이미 성공한 주문 응답으로 전파하지 않는다")
    void isolatesCommittedDeliveryFailure() {
        // given
        StockStreamHub hub = mock(StockStreamHub.class);
        doThrow(new IllegalStateException("전달 실패")).when(hub).publish(1, Set.of(StockChange.SALES));
        StockChangeListener listener = new StockChangeListener(hub);

        // when / then
        assertThatCode(() -> listener.onChanged(new StockChangedEvent(1, Set.of(StockChange.SALES))))
                .doesNotThrowAnyException();
    }
}
