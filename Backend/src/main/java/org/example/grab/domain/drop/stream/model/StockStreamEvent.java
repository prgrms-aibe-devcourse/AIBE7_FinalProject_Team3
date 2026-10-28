package org.example.grab.domain.drop.stream.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class StockStreamEvent {

    private StockStreamEvent() {
    }

    public record Ready(List<Long> dropIds, OffsetDateTime serverTime) {
        public Ready {
            dropIds = List.copyOf(dropIds);
        }
    }

    public record Changed(long dropId, Set<StockChange> changes) {
        public Changed {
            changes = Set.copyOf(changes);
        }
    }

    public enum CloseReason {
        RECONNECT_REQUIRED(true);

        private final boolean reconnect;

        CloseReason(boolean reconnect) {
            this.reconnect = reconnect;
        }

        public Closed event() {
            return new Closed(name(), reconnect);
        }
    }

    public record Closed(String reason, boolean reconnect) {
    }
}
