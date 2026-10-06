package org.example.grab.domain.dashboard.dto;

import java.time.OffsetDateTime;

public record DropStatsResponse(
        Long dropId,
        String name,
        String status,
        OffsetDateTime saleEndsAt,
        long activeWishCount,
        long availableStock,
        long reservedStock,
        long soldStock,
        long orderCount,
        long salesAmount
) {

    public static DropStatsResponse from(DropStatsProjection projection) {
        return new DropStatsResponse(projection.dropId(), projection.name(), projection.status(),
                projection.saleEndsAt(), projection.activeWishCount(), projection.availableStock(),
                projection.reservedStock(), projection.soldStock(), projection.orderCount(),
                projection.salesAmount());
    }
}
