package org.example.grab.domain.drop.stream.model;

import java.util.Set;

public record StockChangedEvent(long dropId, Set<StockChange> changes) {
    public StockChangedEvent {
        changes = Set.copyOf(changes);
        if (dropId <= 0 || changes.isEmpty()) {
            throw new IllegalArgumentException("상품과 변경 종류가 필요합니다.");
        }
    }
}
