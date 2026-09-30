package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

public record SellerDropListResponse(
        Long dropId,
        String name,
        String thumbnailUrl,
        DropStatus status,
        Long minPrice,
        OffsetDateTime createdAt
) {
    public static SellerDropListResponse from(SellerDropListProjection projection) {
        return new SellerDropListResponse(
                projection.getDropId(),
                projection.getName(),
                projection.getThumbnailUrl(),
                projection.getStatus(),
                projection.getMinPrice(),
                projection.getCreatedAt());
    }
}
