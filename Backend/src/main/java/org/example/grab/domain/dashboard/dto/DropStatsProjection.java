package org.example.grab.domain.dashboard.dto;

import java.time.OffsetDateTime;

// jdbcTemplate를 쓰기 때문에 interface(JPA용)가 아닌 record 사용
public record DropStatsProjection(
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
}
