package org.example.grab.domain.drop.upload.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImageUploadUrlResponse(
        UUID imageId,
        String uploadUrl,
        String imageUrl,
        OffsetDateTime expiresAt
) {
}
