package org.example.grab.domain.drop.aidraft;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// 업로드 URL 발급 응답의 imageId·imageUrl을 그대로 받는다(DROP.md 2.8). 폼 순서대로 최대 3장
public record DropAiDraftRequest(
        @NotNull @Size(min = 1, max = 3) List<@Valid @NotNull Image> images
) {

    @Schema(name = "DropAiDraftImage")
    public record Image(@NotNull UUID imageId, @NotBlank @Size(max = 500) String imageUrl) {
    }
}
