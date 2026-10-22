package org.example.grab.domain.user.service;

import org.example.grab.global.storage.ProfileImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;

import java.util.ArrayList;
import java.util.List;

// 공개 URL 규칙만 실제 Supabase 클라이언트와 같게 만든 테스트 대역. 서명 발급은 쓰지 않고, 지운 키를 기록한다
class FakeProfileImageStorage implements ProfileImageStorage {

    static final String PUBLIC_BASE = "https://project.supabase.co/storage/v1/object/public/profile-images/";

    final List<String> deletedKeys = new ArrayList<>();

    @Override
    public SignedUploadUrl createSignedUploadUrl(String objectKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String publicUrl(String objectKey) {
        return PUBLIC_BASE + objectKey;
    }

    @Override
    public void delete(String objectKey) {
        deletedKeys.add(objectKey);
    }
}
