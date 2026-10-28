package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.response.ProfileImageResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.event.ProfileImageDiscardedEvent;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/*
    GR-130 M05-02: 프로필 이미지 등록·삭제의 판정 순서(M00-06)와 이전 객체 삭제 이벤트 발행 조건(M00-04).
    저장소·이벤트 발행은 mock이고, URL 검증은 실제 ProfileImageUrlVerifier에 공개 URL 규칙만 같은 테스트 대역을 붙인다.
    커밋 후에만 지우는 동작은 리스너(ProfileImageCleanupListenerTest)와 통합 테스트(ProfileImageApiIntegrationTest)가 맡는다.
 */
class ProfileImageCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final String IMAGE_A = "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b";
    private static final String IMAGE_B = "2e1a0f9d-8c7b-4a6b-9523-7d6e5f4a3b2c";

    private UserRepository userRepository;
    private ApplicationEventPublisher eventPublisher;
    private ProfileImageCommandService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ProfileImageCommandService(
                userRepository, new ProfileImageUrlVerifier(new FakeProfileImageStorage()), eventPublisher);
        user = User.createLocal("user@example.com", "$argon2id$hash", "드롭헌터");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);
    }

    private String ownedUrl(String imageId) {
        return FakeProfileImageStorage.PUBLIC_BASE + "profiles/" + user.getUuid() + "/" + imageId + ".png";
    }

    private String ownedKey(String imageId) {
        return "profiles/" + user.getUuid() + "/" + imageId + ".png";
    }

    private void givenLockedUser(String currentImageUrl) {
        if (currentImageUrl != null) {
            user.changeProfileImage(currentImageUrl);
        }
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
    }

    // 등록

    @Test
    @DisplayName("이미지가 없던 회원이 본인 URL을 등록하면 저장하고 public_id·URL을 돌려주며 지울 객체는 없다")
    void registersFirstImage() {
        // given
        givenLockedUser(null);

        // when
        ProfileImageResponse response = service.register(USER_ID, ownedUrl(IMAGE_A));

        // then
        assertThat(user.getProfileImageUrl()).isEqualTo(ownedUrl(IMAGE_A));
        assertThat(response.userId()).isEqualTo(user.getUuid());
        assertThat(response.profileImageUrl()).isEqualTo(ownedUrl(IMAGE_A));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("다른 본인 URL로 교체하면 이전 객체 삭제 이벤트를 이전 키로 발행한다")
    void replacesImageAndDiscardsPrevious() {
        // given
        givenLockedUser(ownedUrl(IMAGE_A));

        // when
        service.register(USER_ID, ownedUrl(IMAGE_B));

        // then
        assertThat(user.getProfileImageUrl()).isEqualTo(ownedUrl(IMAGE_B));
        verify(eventPublisher).publishEvent(new ProfileImageDiscardedEvent(USER_ID, ownedKey(IMAGE_A)));
    }

    @Test
    @DisplayName("현재와 같은 URL을 다시 등록하면 성공하고 지우지 않는다")
    void reRegisteringSameUrlDoesNotDiscard() {
        // given
        givenLockedUser(ownedUrl(IMAGE_A));

        // when
        ProfileImageResponse response = service.register(USER_ID, ownedUrl(IMAGE_A));

        // then
        assertThat(response.profileImageUrl()).isEqualTo(ownedUrl(IMAGE_A));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/images/profile-uuid.jpg",
            "https://project.supabase.co/storage/v1/object/public/drop-images/images/" + IMAGE_B + ".png"
    })
    @DisplayName("본인 발급 형식이 아닌 이전 값은 교체해도 지우지 않는다")
    void doesNotDiscardForeignPreviousValue(String previous) {
        // given: 형식 밖 값이 들어 있는 행(외부 URL·DROP 이미지)
        givenLockedUser(previous);

        // when
        service.register(USER_ID, ownedUrl(IMAGE_A));

        // then
        assertThat(user.getProfileImageUrl()).isEqualTo(ownedUrl(IMAGE_A));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("다른 회원 경로였던 이전 값은 교체해도 지우지 않는다")
    void doesNotDiscardOtherMembersObject() {
        // given
        String otherMembers = FakeProfileImageStorage.PUBLIC_BASE + "profiles/" + UUID.randomUUID() + "/" + IMAGE_B + ".png";
        givenLockedUser(otherMembers);

        // when
        service.register(USER_ID, ownedUrl(IMAGE_A));

        // then
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/images/profile-uuid.jpg",
            "https://project.supabase.co/storage/v1/object/public/drop-images/images/" + IMAGE_A + ".png",
            "OTHER_MEMBER",
            "GIF",
            "QUERY"
    })
    @DisplayName("외부·DROP 이미지·다른 회원·허용하지 않는 확장자·쿼리 문자열 URL은 VALIDATION_FAILED(imageUrl)이고 값이 그대로다")
    void rejectsNotOwnedUrl(String kind) {
        // given
        givenLockedUser(ownedUrl(IMAGE_A));
        String imageUrl = switch (kind) {
            case "OTHER_MEMBER" -> FakeProfileImageStorage.PUBLIC_BASE + "profiles/" + UUID.randomUUID() + "/" + IMAGE_B + ".png";
            case "GIF" -> ownedUrl(IMAGE_B).replace(".png", ".gif");
            case "QUERY" -> ownedUrl(IMAGE_B) + "?v=1";
            default -> kind;
        };

        // when & then
        assertThatThrownBy(() -> service.register(USER_ID, imageUrl))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field).containsExactly("imageUrl");
                });
        assertThat(user.getProfileImageUrl()).isEqualTo(ownedUrl(IMAGE_A));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // M00-06 판정 순서: 회원 상태를 URL 검증보다 먼저 본다
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지·탈퇴 회원은 URL이 잘못돼도 AUTHENTICATION_REQUIRED가 먼저다")
    void rejectsInactiveUserBeforeUrlCheck(UserStatus status) {
        // given
        ReflectionTestUtils.setField(user, "status", status);
        givenLockedUser(null);

        // when & then
        assertThatThrownBy(() -> service.register(USER_ID, "https://cdn.example.com/x.png"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> service.remove(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("회원이 없으면 등록·삭제 모두 AUTHENTICATION_REQUIRED다")
    void rejectsMissingUser() {
        // given
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.register(USER_ID, ownedUrl(IMAGE_A)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> service.remove(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    // 삭제

    @Test
    @DisplayName("삭제하면 null로 저장하고 이전 객체 삭제 이벤트를 발행한다")
    void removesImageAndDiscardsPrevious() {
        // given
        givenLockedUser(ownedUrl(IMAGE_A));

        // when
        service.remove(USER_ID);

        // then
        assertThat(user.getProfileImageUrl()).isNull();
        verify(eventPublisher).publishEvent(new ProfileImageDiscardedEvent(USER_ID, ownedKey(IMAGE_A)));
    }

    @Test
    @DisplayName("이미지가 없을 때 삭제해도 성공하고 지울 객체가 없다")
    void removingWithoutImageSucceeds() {
        // given
        givenLockedUser(null);

        // when
        service.remove(USER_ID);

        // then
        assertThat(user.getProfileImageUrl()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("본인 발급 형식이 아닌 값을 삭제하면 null로 저장하지만 객체는 지우지 않는다")
    void removingForeignValueDoesNotDiscard() {
        // given
        givenLockedUser("https://cdn.example.com/images/profile-uuid.jpg");

        // when
        service.remove(USER_ID);

        // then
        assertThat(user.getProfileImageUrl()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }
}
