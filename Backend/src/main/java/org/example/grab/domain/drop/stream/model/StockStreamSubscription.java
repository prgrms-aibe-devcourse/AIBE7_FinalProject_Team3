package org.example.grab.domain.drop.stream.model;

import java.util.List;
import java.util.UUID;

public record StockStreamSubscription(UUID id, List<Long> dropIds) {
    public StockStreamSubscription {
        dropIds = List.copyOf(dropIds);
    }
}
