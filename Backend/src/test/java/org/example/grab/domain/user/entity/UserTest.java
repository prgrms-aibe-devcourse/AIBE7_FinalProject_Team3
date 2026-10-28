package org.example.grab.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    // 테스트용 입력값
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final String NICKNAME = "홍길동";
    private static final String PROFILE_IMAGE_URL = "https://project.supabase.co/storage/v1/object/public/profile-images/"
            + "profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.png";
    private static final String OTHER_PROFILE_IMAGE_URL = "https://project.supabase.co/storage/v1/object/public/profile-images/"
            + "profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/2e1f0a9d-8c7b-4a6e-9523-7d6e5f4a3b2c.webp";

    @Test
    @DisplayName("LOCAL 회원은 USER, ACTIVE, LOCAL 기본값으로 생성된다")
    void createLocalAppliesDefaults() {
        // when
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);

        // then
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(user.getNickname()).isEqualTo(NICKNAME);
        assertThat(user.getProfileImageUrl()).isNull();
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(user.getId()).isNull();
    }

    @Test
    @DisplayName("LOCAL 회원은 생성 시 서로 다른 공개 UUID를 가진다")
    void createLocalGeneratesPublicUuid() {
        // when
        User first = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);
        User second = User.createLocal("other@example.com", PASSWORD_HASH, NICKNAME);

        // then
        assertThat(first.getUuid()).isNotNull();
        assertThat(second.getUuid()).isNotNull();
        assertThat(first.getUuid()).isNotEqualTo(second.getUuid());
    }

    @ParameterizedTest // 같은 테스트를 값만 바꿔 여러번 실행
    @NullAndEmptySource // passwordHash 파라미터에 null, "" 들어감
    @ValueSource(strings = {" ", "\t"}) // passwordHash 파라미터에 " ", "\t" 차례대로 들어감
    @DisplayName("LOCAL 회원은 비밀번호 해시가 없으면 생성할 수 없다")
    void createLocalRejectsMissingPasswordHash(String passwordHash) {
        // when & then
        //
        assertThatThrownBy(() -> User.createLocal(EMAIL, passwordHash, NICKNAME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LOCAL 회원은 제공자 식별자가 없다")
    void createLocalHasNoProviderUserId() {
        assertThat(User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME).getProviderUserId()).isNull();
    }

    // GR-46 M00-01: 소셜 회원은 비밀번호 해시 없이 제공자 식별자를 가진다(MEMBER_AUTH.md 1.6)
    @Test
    @DisplayName("소셜 회원은 비밀번호 해시 없이 USER, ACTIVE와 제공자·식별자로 생성된다")
    void createSocialAppliesDefaults() {
        // when
        User user = User.createSocial(EMAIL, AuthProvider.KAKAO, "sub-1", NICKNAME);

        // then
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getNickname()).isEqualTo(NICKNAME);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getProvider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(user.getProviderUserId()).isEqualTo("sub-1");
        assertThat(user.getUuid()).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("소셜 회원은 제공자 식별자가 없으면 생성할 수 없다")
    void createSocialRejectsMissingProviderUserId(String providerUserId) {
        assertThatThrownBy(() -> User.createSocial(EMAIL, AuthProvider.GOOGLE, providerUserId, NICKNAME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("소셜 회원의 제공자는 LOCAL이거나 비어 있을 수 없다")
    void createSocialRejectsLocalOrMissingProvider() {
        assertThatThrownBy(() -> User.createSocial(EMAIL, AuthProvider.LOCAL, "sub-1", NICKNAME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.createSocial(EMAIL, null, "sub-1", NICKNAME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("닉네임을 받은 값 그대로 바꾼다")
    void changeNicknameReplacesNickname() {
        // given
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);

        // when
        user.changeNickname("Grab_2");

        // then: 대소문자는 입력 그대로 저장한다(MEMBER_AUTH.md 1.2.3)
        assertThat(user.getNickname()).isEqualTo("Grab_2");
    }

    @Test
    @DisplayName("닉네임을 null로 바꿀 수 없고 기존 값이 유지된다")
    void changeNicknameRejectsNull() {
        // given
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);

        // when & then
        assertThatThrownBy(() -> user.changeNickname(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(user.getNickname()).isEqualTo(NICKNAME);
    }

    @Test
    @DisplayName("프로필 이미지를 받은 URL로 등록하고 다시 바꾼다")
    void changeProfileImageReplacesUrl() {
        // given
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);

        // when & then
        user.changeProfileImage(PROFILE_IMAGE_URL);
        assertThat(user.getProfileImageUrl()).isEqualTo(PROFILE_IMAGE_URL);
        user.changeProfileImage(OTHER_PROFILE_IMAGE_URL);
        assertThat(user.getProfileImageUrl()).isEqualTo(OTHER_PROFILE_IMAGE_URL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("프로필 이미지를 빈 값으로 바꿀 수 없고 기존 값이 유지된다")
    void changeProfileImageRejectsBlank(String profileImageUrl) {
        // given
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);
        user.changeProfileImage(PROFILE_IMAGE_URL);

        // when & then: 지우기는 removeProfileImage로만 한다
        assertThatThrownBy(() -> user.changeProfileImage(profileImageUrl))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getProfileImageUrl()).isEqualTo(PROFILE_IMAGE_URL);
    }

    @Test
    @DisplayName("프로필 이미지를 지우면 null이 되고, 없을 때 지워도 null이다")
    void removeProfileImageClearsUrl() {
        // given
        User user = User.createLocal(EMAIL, PASSWORD_HASH, NICKNAME);
        user.changeProfileImage(PROFILE_IMAGE_URL);

        // when & then
        user.removeProfileImage();
        assertThat(user.getProfileImageUrl()).isNull();
        user.removeProfileImage();
        assertThat(user.getProfileImageUrl()).isNull();
    }
}
