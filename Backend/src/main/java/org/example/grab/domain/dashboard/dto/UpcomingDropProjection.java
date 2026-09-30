package org.example.grab.domain.dashboard.dto;

import java.time.OffsetDateTime;

// jdbcTemplate를 쓰기 때문에 interface(JPA용)가 아닌 record 사용
public record UpcomingDropProjection(
        Long dropId,
        String name,
        String status,
        OffsetDateTime upcomingAt
) {
}
