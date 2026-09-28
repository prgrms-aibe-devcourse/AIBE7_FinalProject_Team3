package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

public record DropPublishResponse(
        Long dropId,
        DropStatus status,
        OffsetDateTime publishedAt
) {

    public static DropPublishResponse from(Drop drop) {
        return new DropPublishResponse(drop.getId(), drop.getStatus(), drop.getPublishedAt());
    }
}
