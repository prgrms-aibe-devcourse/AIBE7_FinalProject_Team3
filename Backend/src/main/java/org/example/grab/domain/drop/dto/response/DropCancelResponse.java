package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

public record DropCancelResponse(
        Long dropId,
        DropStatus status,
        OffsetDateTime canceledAt
) {

    public static DropCancelResponse from(Drop drop) {
        return new DropCancelResponse(drop.getId(), drop.getStatus(), drop.getClosedAt());
    }
}
