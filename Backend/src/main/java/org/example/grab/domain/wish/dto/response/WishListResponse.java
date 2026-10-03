package org.example.grab.domain.wish.dto.response;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.wish.dto.WishListProjection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record WishListResponse(
        Long dropId,
        String name,
        String thumbnailUrl,
        Long minPrice,
        DropStatus status,
        OffsetDateTime wishedAt
) {

    // DROP이 GRAB·ENDED·CANCELED로 바뀌어도 WISH가 활성이라면 현재 status를 그대로 표시한다.
    public static WishListResponse from(WishListProjection projection) {
        return new WishListResponse(
                projection.getDropId(),
                projection.getName(),
                projection.getThumbnailUrl(),
                projection.getMinPrice(),
                DropStatus.valueOf(projection.getStatus()),
                toOffsetDateTime(projection.getWishedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
