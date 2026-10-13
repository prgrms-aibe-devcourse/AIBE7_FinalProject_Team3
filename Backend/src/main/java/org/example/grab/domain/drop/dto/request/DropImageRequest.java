package org.example.grab.domain.drop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// altText는 선택이다. 누락/null이면 기존 이미지는 저장된 설명을, 새 이미지는 상품명을 쓴다(DROP.md 2.1).
public record DropImageRequest(
        @NotNull UUID imageId,
        @NotBlank @Size(max = 500) String imageUrl,
        @Size(max = 300) String altText
) {
}
