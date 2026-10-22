package org.example.grab.domain.user.service;

import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ProfileImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// GR-130 M05-02: 프로필 업로드 URL 발급의 판정 순서(형식·크기 → 회원 ACTIVE → 발급, M00-06)와 객체 키 형식(M00-02)
class ProfileImageUploadServiceTest {

    private static final Long USER_ID = 7L;

    private UserRepository userRepository;
    private ProfileImageStorage profileImageStorage;
    private ProfileImageUploadService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileImageStorage = mock(ProfileImageStorage.class);
        service = new ProfileImageUploadService(userRepository, profileImageStorage);
    }

    private static User user(UserStatus status) {
        User user = User.createLocal("user@example.com", "$argon2id$hash", "드롭헌터");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    @ParameterizedTest
    @CsvSource({"image/jpeg,jpg", "image/png,png", "image/webp,webp"})
    @DisplayName("본인 public_id가 들어간 profiles/{publicId}/{imageId}.{ext} 키로 발급하고 2시간 만료를 담는다")
    void issue(String contentType, String extension) {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileImageStorage.createSignedUploadUrl(anyString()))
                .willReturn(new SignedUploadUrl("https://storage.example.com/signed", "https://storage.example.com/public"));

        // when
        OffsetDateTime before = OffsetDateTime.now();
        ImageUploadUrlResponse response = service.issue(USER_ID, contentType, ProfileImageUploadService.MAX_FILE_SIZE);
        OffsetDateTime after = OffsetDateTime.now();

        // then: 원본 파일명은 서비스로 넘어오지 않으므로 키는 서버가 정한 값만으로 이뤄진다
        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(profileImageStorage).createSignedUploadUrl(objectKey.capture());
        assertThat(objectKey.getValue())
                .isEqualTo("profiles/" + user.getUuid() + "/" + response.imageId() + "." + extension);
        assertThat(response.uploadUrl()).isEqualTo("https://storage.example.com/signed");
        assertThat(response.imageUrl()).isEqualTo("https://storage.example.com/public");
        assertThat(response.expiresAt()).isBetween(
                before.plus(ProfileImageUploadService.UPLOAD_URL_TTL), after.plus(ProfileImageUploadService.UPLOAD_URL_TTL));
    }

    @Test
    @DisplayName("허용하지 않는 형식과 5MB 초과는 함께 VALIDATION_FAILED이고 회원 조회·발급을 하지 않는다")
    void rejectsTypeAndSizeBeforeUserLookup() {
        // when & then
        assertThatThrownBy(() -> service.issue(USER_ID, "image/gif", ProfileImageUploadService.MAX_FILE_SIZE + 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("contentType", "fileSize");
                });
        verify(userRepository, never()).findById(any());
        verify(profileImageStorage, never()).createSignedUploadUrl(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지·탈퇴 회원은 AUTHENTICATION_REQUIRED이고 발급하지 않는다")
    void rejectsInactiveUser(UserStatus status) {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(status)));

        // when & then
        assertThatThrownBy(() -> service.issue(USER_ID, "image/png", 1024L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        verify(profileImageStorage, never()).createSignedUploadUrl(anyString());
    }

    @Test
    @DisplayName("회원이 없으면 AUTHENTICATION_REQUIRED이고 발급하지 않는다")
    void rejectsMissingUser() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.issue(USER_ID, "image/png", 1024L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        verify(profileImageStorage, never()).createSignedUploadUrl(anyString());
    }
}
