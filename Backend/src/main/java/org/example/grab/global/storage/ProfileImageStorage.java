package org.example.grab.global.storage;

// 회원 프로필 이미지 공개 버킷(GR-130). DROP 이미지용 ImageStorage와 버킷이 달라 따로 주입받는다.
// ImageStorage를 상속하지 않는다. 상속하면 ImageStorage 주입 지점에 후보 빈이 둘이 된다.
public interface ProfileImageStorage {

    SignedUploadUrl createSignedUploadUrl(String objectKey);

    String publicUrl(String objectKey);
}
