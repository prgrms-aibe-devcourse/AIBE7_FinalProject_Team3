package org.example.grab.domain.dashboard.dto;

import java.time.OffsetDateTime;

public record UpcomingDropProjection(
        Long dropId,
        String name,
        String status,
        OffsetDateTime upcomingAt
) {
}
