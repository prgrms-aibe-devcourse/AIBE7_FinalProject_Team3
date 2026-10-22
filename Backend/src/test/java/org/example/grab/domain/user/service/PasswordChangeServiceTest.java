package org.example.grab.domain.user.service;

import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// GR-129 M05-02: 비밀번호 변경의 판정 순서(상태 → 소셜 → 불일치 → 같은 값 → 저장, M00-04)와 조건부 갱신 0행(M03-02). 저장소·인코더는 mock이다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PasswordChangeServiceTest {

    private static final Long USER_ID = 7L;
    private static final String READ_HASH = "$argon2id$read";
    private static final String NEW_HASH = "$argon2id$new";
    private static final String CURRENT = "Password123!";
    private static final String NEW = "NewPassword456!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordChangeService service;

    private static User localUser(UserStatus status) {
        User user = User.createLocal("user@example.com", READ_HASH, "드롭헌터");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    private static void assertErrorCode(Runnable call, Object errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 새 해시로 읽은 해시가 그대로일 때만 바꾸고, 원문·해시를 로그에 남기지 않는다")
    void changesPassword(CapturedOutput output) {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(CURRENT, READ_HASH)).willReturn(true);
        given(passwordEncoder.encode(NEW)).willReturn(NEW_HASH);
        given(userRepository.updatePasswordHashIfUnchanged(USER_ID, READ_HASH, NEW_HASH)).willReturn(1);

        // when
        service.changePassword(USER_ID, CURRENT, NEW);

        // then
        then(userRepository).should().updatePasswordHashIfUnchanged(USER_ID, READ_HASH, NEW_HASH);
        assertThat(output).contains("비밀번호 변경: userId=7").doesNotContain(CURRENT, NEW, READ_HASH, NEW_HASH);
    }

    @Test
    @DisplayName("검증과 저장 사이 비밀번호가 바뀌어 저장이 0행이면 PASSWORD_MISMATCH다")
    void rejectsConcurrentChange() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(CURRENT, READ_HASH)).willReturn(true);
        given(passwordEncoder.encode(NEW)).willReturn(NEW_HASH);
        given(userRepository.updatePasswordHashIfUnchanged(USER_ID, READ_HASH, NEW_HASH)).willReturn(0);

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, CURRENT, NEW), UserErrorCode.PASSWORD_MISMATCH);
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 PASSWORD_MISMATCH이고 해시·저장을 하지 않는다")
    void rejectsWrongCurrentPassword() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches("Wrong123!", READ_HASH)).willReturn(false);

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, "Wrong123!", NEW), UserErrorCode.PASSWORD_MISMATCH);
        then(passwordEncoder).should(never()).encode(anyString());
        then(userRepository).should(never()).updatePasswordHashIfUnchanged(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 새 비밀번호가 같아도 PASSWORD_UNCHANGED가 아니라 PASSWORD_MISMATCH다")
    void checksMismatchBeforeUnchanged() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches("Wrong123!", READ_HASH)).willReturn(false);

        // when & then: 현재 비밀번호를 모르는 요청에 같은 값인지가 드러나지 않는다(M00-04)
        assertErrorCode(() -> service.changePassword(USER_ID, "Wrong123!", "Wrong123!"), UserErrorCode.PASSWORD_MISMATCH);
    }

    @Test
    @DisplayName("현재 비밀번호가 맞고 새 비밀번호가 같으면 PASSWORD_UNCHANGED이고 저장하지 않는다")
    void rejectsUnchangedPassword() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(CURRENT, READ_HASH)).willReturn(true);

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, CURRENT, CURRENT), UserErrorCode.PASSWORD_UNCHANGED);
        then(passwordEncoder).should(never()).encode(anyString());
        then(userRepository).should(never()).updatePasswordHashIfUnchanged(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("새 비밀번호는 대소문자가 다르면 다른 값이다")
    void treatsCaseDifferenceAsChanged() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(CURRENT, READ_HASH)).willReturn(true);
        given(passwordEncoder.encode("password123!")).willReturn(NEW_HASH);
        given(userRepository.updatePasswordHashIfUnchanged(USER_ID, READ_HASH, NEW_HASH)).willReturn(1);

        // when
        service.changePassword(USER_ID, CURRENT, "password123!");

        // then
        then(userRepository).should().updatePasswordHashIfUnchanged(USER_ID, READ_HASH, NEW_HASH);
    }

    @ParameterizedTest
    @EnumSource(value = AuthProvider.class, names = "LOCAL", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("소셜 회원은 PASSWORD_NOT_AVAILABLE이고 비밀번호를 검증하지 않는다")
    void rejectsSocialUser(AuthProvider provider) {
        // given
        User user = User.createSocial("user@example.com", provider, "sub-1", "드롭헌터");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, CURRENT, NEW), UserErrorCode.PASSWORD_NOT_AVAILABLE);
        then(passwordEncoder).should(never()).matches(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지·탈퇴 회원은 소셜·비밀번호 판정보다 먼저 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsInactiveUserFirst(UserStatus status) {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(status)));

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, CURRENT, NEW), CommonErrorCode.AUTHENTICATION_REQUIRED);
        then(passwordEncoder).should(never()).matches(any(), any());
    }

    @Test
    @DisplayName("회원이 없으면 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsMissingUser() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertErrorCode(() -> service.changePassword(USER_ID, CURRENT, NEW), CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("인코더가 빈 해시를 돌려주면 저장하지 않는다")
    void doesNotStoreBlankHash() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(localUser(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(CURRENT, READ_HASH)).willReturn(true);
        given(passwordEncoder.encode(NEW)).willReturn(" ");

        // when & then
        assertThatThrownBy(() -> service.changePassword(USER_ID, CURRENT, NEW)).isInstanceOf(IllegalStateException.class);
        then(userRepository).should(never()).updatePasswordHashIfUnchanged(anyLong(), anyString(), anyString());
    }
}
