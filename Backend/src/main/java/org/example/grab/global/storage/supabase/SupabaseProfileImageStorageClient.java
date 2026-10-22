package org.example.grab.global.storage.supabase;

import lombok.RequiredArgsConstructor;
import org.example.grab.global.storage.ProfileImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;

/**
 * 프로필 이미지 버킷(profileImagesBucket, GR-130)에 묶인 SupabaseStorageClient로 서명 업로드 URL·공개 URL을 만들고 객체를 지운다.
 * 호출·오류 변환·Secret Key 처리는 SupabaseStorageClient와 같다.
 */
@RequiredArgsConstructor
public class SupabaseProfileImageStorageClient implements ProfileImageStorage {

    private final SupabaseStorageClient delegate;

    @Override
    public SignedUploadUrl createSignedUploadUrl(String objectKey) {
        return delegate.createSignedUploadUrl(objectKey);
    }

    // 프로필 등록 시 imageUrl이 본인 경로의 공개 URL인지 검증할 때도 쓴다(GR-130 M00-02).
    @Override
    public String publicUrl(String objectKey) {
        return delegate.publicUrl(objectKey);
    }

    // 교체·삭제한 이전 프로필 이미지를 커밋 후 지울 때 쓴다(GR-130 M00-04).
    @Override
    public void delete(String objectKey) {
        delegate.delete(objectKey);
    }
}
