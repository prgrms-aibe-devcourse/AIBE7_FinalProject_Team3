package org.example.grab.domain.drop.upload.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DROP 이미지 업로드 URL 발급(IMAGE_UPLOAD.md 1.1). 허용 형식·크기는 DROP 이미지 정책이라 이 계층에 둔다.
 */
@Service
@RequiredArgsConstructor
public class DropImageUploadService {

    // 버킷 설정(allowedMimeTypes·fileSizeLimit)과 같은 값을 유지한다.
    public static final long MAX_FILE_SIZE = 5_242_880L;
    // Supabase 서명 업로드 URL 만료는 2시간 고정이다(D4).
    public static final Duration UPLOAD_URL_TTL = Duration.ofHours(2);

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final ImageStorage imageStorage;

    public ImageUploadUrlResponse issue(String contentType, long fileSize) {
        String extension = validate(contentType, fileSize);
        UUID imageId = UUID.randomUUID();
        // 객체 키에는 원본 파일명을 쓰지 않고, 확장자는 contentType에서 결정한다.
        SignedUploadUrl signed = imageStorage.createSignedUploadUrl("images/" + imageId + "." + extension);
        return new ImageUploadUrlResponse(
                imageId, signed.uploadUrl(), signed.imageUrl(), OffsetDateTime.now().plus(UPLOAD_URL_TTL));
    }

    private String validate(String contentType, long fileSize) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            fieldErrors.add(new ErrorResponse.FieldError("contentType", "지원하지 않는 이미지 형식입니다."));
        }
        if (fileSize > MAX_FILE_SIZE) {
            fieldErrors.add(new ErrorResponse.FieldError("fileSize", "이미지 크기는 5MB 이하여야 합니다."));
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, fieldErrors);
        }
        return extension;
    }
}
