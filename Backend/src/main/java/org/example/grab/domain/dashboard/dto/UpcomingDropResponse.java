package org.example.grab.domain.dashboard.dto;

import java.time.OffsetDateTime;

public record UpcomingDropResponse(
        Long dropId,
        String name,
        String status,
        UpcomingDropEventType eventType,
        OffsetDateTime upcomingAt
) {

    public static UpcomingDropResponse from(UpcomingDropProjection projection, UpcomingDropEventType eventType) {
        return new UpcomingDropResponse(projection.getDropId(), projection.getName(), projection.getStatus(),
                eventType, projection.getUpcomingAt());
    }
}
