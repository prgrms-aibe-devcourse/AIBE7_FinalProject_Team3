package org.example.grab.domain.drop.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.entity.DropStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record PublicDropListResponse(
        Long dropId,
        String name,
        String brandName,
        @Schema(nullable = true) String thumbnailUrl,
        @Schema(nullable = true) Long minPrice,
        DropCategoryResponse category,
        // 공유 DropStatus에는 DRAFT·CANCELED가 있지만 공개 목록 응답은 세 값만 가진다. 문서 스키마만 필드 단위로 좁히고
        // Java 타입·직렬화는 그대로 둔다. 전역 스키마를 제한하면 상세(CANCELED)·판매자(DRAFT)가 함께 잘린다.
        @Schema(implementation = String.class, allowableValues = {"WISH", "GRAB", "ENDED"})
        DropStatus status,
        boolean soldOut,
        long availableStock,
        long wishCount,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt
) {

    public static PublicDropListResponse from(PublicDropListProjection projection) {
        return new PublicDropListResponse(
                projection.getDropId(),
                projection.getName(),
                projection.getBrandName(),
                projection.getThumbnailUrl(),
                projection.getMinPrice(),
                new DropCategoryResponse(projection.getCategoryId(), projection.getCategoryName()),
                DropStatus.valueOf(projection.getStatus()),
                Boolean.TRUE.equals(projection.getSoldOut()),
                projection.getAvailableStock() == null ? 0L : projection.getAvailableStock(),
                projection.getWishCount() == null ? 0L : projection.getWishCount(),
                toOffsetDateTime(projection.getSaleStartsAt()),
                toOffsetDateTime(projection.getSaleEndsAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
