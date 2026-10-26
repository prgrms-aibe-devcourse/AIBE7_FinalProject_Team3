package org.example.grab.domain.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// GR-130 M05-02: 본인 발급 URL 판정(M00-02). 공개 URL 규칙은 테스트 대역이 실제 클라이언트와 같게 만든다
class ProfileImageUrlVerifierTest {

    private static final UUID OWNER = UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8");
    private static final UUID OTHER = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final String IMAGE_ID = "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b";
    private static final String OWNER_BASE = FakeProfileImageStorage.PUBLIC_BASE + "profiles/" + OWNER + "/";
    // @ValueSource에는 상수식만 쓸 수 있어 본인 경로 앞부분은 자리표시자로 넣고 테스트에서 바꾼다
    private static final String OWNER_BASE_PLACEHOLDER = "{owner}";

    private final ProfileImageUrlVerifier verifier = new ProfileImageUrlVerifier(new FakeProfileImageStorage());

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "png", "webp"})
    @DisplayName("본인 경로의 UUID·허용 확장자 공개 URL이면 객체 키를 돌려준다")
    void acceptsOwnedUrl(String extension) {
        // when & then
        assertThat(verifier.ownedObjectKey(OWNER, OWNER_BASE + IMAGE_ID + "." + extension))
                .contains("profiles/" + OWNER + "/" + IMAGE_ID + "." + extension);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 외부 URL, 다른 버킷(DROP 이미지)
            "https://cdn.example.com/images/profile-uuid.jpg",
            "https://project.supabase.co/storage/v1/object/public/drop-images/images/" + IMAGE_ID + ".png",
            // 허용하지 않는 확장자, 확장자 없음, 대문자 확장자
            OWNER_BASE_PLACEHOLDER + IMAGE_ID + ".gif",
            OWNER_BASE_PLACEHOLDER + IMAGE_ID,
            OWNER_BASE_PLACEHOLDER + IMAGE_ID + ".PNG",
            // 쿼리 문자열·조각
            OWNER_BASE_PLACEHOLDER + IMAGE_ID + ".png?download=1",
            OWNER_BASE_PLACEHOLDER + IMAGE_ID + ".png#x",
            // UUID가 아님·대문자·자릿수 부족
            OWNER_BASE_PLACEHOLDER + "profile.png",
            OWNER_BASE_PLACEHOLDER + "1D0F9E8C-7B6A-4539-8412-6C5D4E3F2A1B.png",
            OWNER_BASE_PLACEHOLDER + "1-1-1-1-1.png",
            // 경로 조작
            OWNER_BASE_PLACEHOLDER + "../" + IMAGE_ID + ".png",
            OWNER_BASE_PLACEHOLDER + "x/../" + IMAGE_ID + ".png",
            OWNER_BASE_PLACEHOLDER + "x%2F" + IMAGE_ID + ".png",
            OWNER_BASE_PLACEHOLDER + IMAGE_ID + "/" + IMAGE_ID + ".png",
            // 빈 값
            "",
            OWNER_BASE_PLACEHOLDER
    })
    @DisplayName("본인 발급 형식이 아니면 빈 값이다")
    void rejectsNotOwnedUrl(String template) {
        // given
        String imageUrl = template.replace(OWNER_BASE_PLACEHOLDER, OWNER_BASE);

        // when & then
        assertThat(verifier.ownedObjectKey(OWNER, imageUrl)).isEmpty();
    }

    @Test
    @DisplayName("다른 회원 경로의 URL은 그 회원에게만 본인 발급이다")
    void rejectsOtherMembersPath() {
        // given
        String otherUrl = FakeProfileImageStorage.PUBLIC_BASE + "profiles/" + OTHER + "/" + IMAGE_ID + ".png";

        // when & then
        assertThat(verifier.ownedObjectKey(OWNER, otherUrl)).isEmpty();
        assertThat(verifier.ownedObjectKey(OTHER, otherUrl)).isPresent();
    }

    @Test
    @DisplayName("null이면 빈 값이다")
    void rejectsNull() {
        // when & then
        assertThat(verifier.ownedObjectKey(OWNER, null)).isEmpty();
    }
}
