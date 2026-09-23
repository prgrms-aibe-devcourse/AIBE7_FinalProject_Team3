package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

public record SellerDropListResponse(
        Long dropId,
        String name,
        DropStatus status,
        Long minPrice,
        OffsetDateTime createdAt
) {
}
