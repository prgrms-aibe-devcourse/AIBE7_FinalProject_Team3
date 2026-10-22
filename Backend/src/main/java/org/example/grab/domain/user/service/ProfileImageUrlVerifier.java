package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.global.storage.ProfileImageStorage;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/*
    프로필 이미지 URL이 회원 본인이 발급받은 객체의 공개 URL인지 확인한다(GR-130 M00-02).
    본인 발급 URL은 profile-images 버킷의 publicUrl(profiles/{회원 public_id}/{UUID}.{허용 확장자})이다(ProfileImageUploadService).
    - 등록(PUT): 요청 imageUrl이 이 형식이 아니면 VALIDATION_FAILED(imageUrl)로 거부한다(M00-06)
    - 이전 객체 삭제: 이전 값이 이 형식일 때만 지운다. 다른 회원·DROP 이미지·형식 밖 값은 지우지 않는다(M00-04 3번)
    저장소 조회·객체 존재 확인은 하지 않는다(DROP isExpectedImageUrl과 같음).

    URL을 해석해 키를 믿지 않고, 꺼낸 조각으로 서버가 공개 URL을 다시 만들어 문자열 전체가 같은지 본다.
    파일명 부분은 소문자 정규형 UUID와 허용 확장자만 받으므로 쿼리 문자열·대소문자 변형·경로 조작(.., 인코딩된 /)은 모두 어긋난다.
 */
@Component
@RequiredArgsConstructor
public class ProfileImageUrlVerifier {

    private final ProfileImageStorage profileImageStorage;

    // 본인 발급 URL이면 그 객체 키를, 아니면 빈 값을 돌려준다. imageUrl이 null이어도 빈 값이다.
    public Optional<String> ownedObjectKey(UUID ownerPublicId, String imageUrl) {
        if (imageUrl == null) {
            return Optional.empty();
        }
        String ownerPrefix = ProfileImageUploadService.OBJECT_KEY_PREFIX + ownerPublicId + "/";
        String expectedUrlPrefix = profileImageStorage.publicUrl(ownerPrefix);
        if (!imageUrl.startsWith(expectedUrlPrefix)) {
            return Optional.empty();
        }

        String fileName = imageUrl.substring(expectedUrlPrefix.length());
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String imageId = fileName.substring(0, dot);
        String extension = fileName.substring(dot + 1);
        if (!isCanonicalUuid(imageId) || !ProfileImageUploadService.ALLOWED_EXTENSIONS.contains(extension)) {
            return Optional.empty();
        }

        String objectKey = ownerPrefix + imageId + "." + extension;
        return profileImageStorage.publicUrl(objectKey).equals(imageUrl) ? Optional.of(objectKey) : Optional.empty();
    }

    // UUID.fromString은 자릿수가 모자란 값("1-1-1-1-1")·대문자도 받으므로, 발급 형식(UUID.toString, 소문자 36자)과 같은지 다시 본다
    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
