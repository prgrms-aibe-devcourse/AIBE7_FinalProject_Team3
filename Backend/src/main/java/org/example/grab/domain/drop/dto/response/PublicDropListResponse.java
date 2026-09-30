package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record PublicDropListResponse(
        Long dropId,
        String name,
        String thumbnailUrl,
        Long minPrice,
        DropCategoryResponse category,
        DropStatus status,
        boolean soldOut,
        long wishCount,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt
) {

    public static PublicDropListResponse from(PublicDropListProjection projection) {
        return new PublicDropListResponse(
                projection.getDropId(),
                projection.getName(),
                projection.getThumbnailUrl(),
                projection.getMinPrice(),
                new DropCategoryResponse(projection.getCategoryId(), projection.getCategoryName()),
                DropStatus.valueOf(projection.getStatus()),
                Boolean.TRUE.equals(projection.getSoldOut()),
                projection.getWishCount() == null ? 0L : projection.getWishCount(),
                toOffsetDateTime(projection.getSaleStartsAt()),
                toOffsetDateTime(projection.getSaleEndsAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
