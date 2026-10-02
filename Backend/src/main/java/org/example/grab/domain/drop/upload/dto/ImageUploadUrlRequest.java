package org.example.grab.domain.drop.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ImageUploadUrlRequest(
        // fileName은 명세 호환용으로 받기만 한다. 객체 키에 쓰지 않고 로그에도 남기지 않는다.
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank String contentType,
        @NotNull @Positive Long fileSize
) {
}
