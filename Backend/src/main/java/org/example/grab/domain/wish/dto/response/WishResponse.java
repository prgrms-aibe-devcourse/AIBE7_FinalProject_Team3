package org.example.grab.domain.wish.dto.response;

import java.time.OffsetDateTime;

public record WishResponse(
        Long dropId,
        boolean wished,
        OffsetDateTime wishedAt,
        String notice
) {
}
