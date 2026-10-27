package org.example.grab.domain.drop.aidraft;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

// 항목마다 검증에 실패하면 null이다. 저장하지 않으며 판매자가 화면에서 항목별로 적용한다(DROP.md 2.8)
public record DropAiDraftResponse(
        String name,
        String description,
        Long categoryId,
        List<ImageAltText> images
) {

    @Schema(name = "DropAiDraftImageAltText")
    public record ImageAltText(UUID imageId, String altText) {
    }
}
