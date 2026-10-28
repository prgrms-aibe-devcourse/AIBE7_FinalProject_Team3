package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ProfileImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/*
    내 프로필 이미지 업로드 URL 발급(GR-130 M00-01). 요청·응답은 DROP 이미지 발급(IMAGE_UPLOAD.md 1.1)과 같은 모양이다.
    판정 순서는 형식·크기 → 회원 존재·ACTIVE → 서명 URL 발급이다(M00-06). 정지·탈퇴 회원은 저장소에 파일을 올리지 못하게
    1.9와 같이 AUTHENTICATION_REQUIRED로 거부한다(M00-07).
    객체 키는 profiles/{회원 public_id}/{imageId}.{확장자}다(M00-02). 등록(PUT) 때 이 경로가 요청자 본인 것인지로 발급 여부를 판단한다.
    서비스 트랜잭션을 두지 않는다. 회원 조회가 끝나면 DB 연결을 돌려주고 Supabase를 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageUploadService {

    // 허용 형식·크기는 DROP 이미지(DropImageUploadService)와 같은 값을 복사해 둔다(M02-02 결정, 공통 상수로 묶지 않음).
    // profile-images 버킷 설정(allowedMimeTypes·fileSizeLimit)과 같은 값을 유지한다(M00-06).
    public static final long MAX_FILE_SIZE = 5_242_880L;
    // Supabase 서명 업로드 URL 만료는 2시간 고정이다.
    public static final Duration UPLOAD_URL_TTL = Duration.ofHours(2);
    public static final String OBJECT_KEY_PREFIX = "profiles/";

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    // 등록(PUT)·이전 객체 삭제 대상 검증(ProfileImageUrlVerifier)이 발급과 같은 확장자만 받도록 함께 쓴다.
    static final Set<String> ALLOWED_EXTENSIONS = Set.copyOf(EXTENSIONS.values());

    private final UserRepository userRepository;
    private final ProfileImageStorage profileImageStorage;

    // userId는 CurrentUserIdProvider가 principal의 public_id로 찾은 내부 ID다
    public ImageUploadUrlResponse issue(Long userId, String contentType, long fileSize) {
        String extension = validate(contentType, fileSize);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> rejected("user_not_found", userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw rejected("user_inactive", userId);
        }

        UUID imageId = UUID.randomUUID();
        // 객체 키에는 원본 파일명을 쓰지 않고, 확장자는 contentType에서 결정한다.
        SignedUploadUrl signed = profileImageStorage.createSignedUploadUrl(
                OBJECT_KEY_PREFIX + user.getUuid() + "/" + imageId + "." + extension);
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

    // 1.9 조회 거부(UserMeQueryService)와 같이 사유와 내부 ID만 남긴다
    private static BusinessException rejected(String reason, Long userId) {
        log.info("프로필 이미지 업로드 URL 발급 거부: reason={}, userId={}", reason, userId);
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
