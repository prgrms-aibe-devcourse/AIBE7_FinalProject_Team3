package org.example.grab.domain.drop.dto;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

public record DropDraftResponse(
        Long dropId,
        DropStatus status,
        OffsetDateTime createdAt
) {

    public static DropDraftResponse from(Drop drop) {
        return new DropDraftResponse(drop.getId(), drop.getStatus(), drop.getCreatedAt());
    }
}
