package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// GR-34 M02-01: 로그인 자격 확인의 판정 순서(M00-03)와 더미 해시 검증. 저장소·인코더는 mock이다
@ExtendWith(MockitoExtension.class)
class UserCredentialServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Password123!";
    private static final String WRONG_PASSWORD = "Wrong123!";
    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final String DUMMY_HASH = "$argon2id$v=19$m=19456,t=2,p=1$ZHVtbXk$ZHVtbXk";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserCredentialService service;

    @BeforeEach
    void setUp() {
        // 생성자가 더미 해시를 한 번 만든다
        given(passwordEncoder.encode(anyString())).willReturn(DUMMY_HASH);
        service = new UserCredentialService(userRepository, passwordEncoder);
    }

    private static User user(UserStatus status) {
        User user = User.createLocal(EMAIL, PASSWORD_HASH, "드롭헌터");
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    // 소셜 회원은 비밀번호 해시가 없다(ck_users_local_password는 LOCAL만 해시를 요구한다)
    private static User socialUser() {
        User user = user(UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "provider", AuthProvider.KAKAO);
        ReflectionTestUtils.setField(user, "passwordHash", null);
        return user;
    }

    private void assertFailsWith(String password, UserErrorCode errorCode) {
        assertThatThrownBy(() -> service.authenticate(EMAIL, password))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    @Test
    @DisplayName("기동 시 같은 인코더로 더미 해시를 한 번 만든다")
    void createsDummyHashOnce() {
        then(passwordEncoder).should(times(1)).encode(anyString());
    }

    @Test
    @DisplayName("ACTIVE 회원의 비밀번호가 맞으면 해시 없이 회원 정보를 돌려준다")
    void returnsLoginUserWhenPasswordMatches() {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).willReturn(true);

        // when
        LoginUser loginUser = service.authenticate(EMAIL, PASSWORD);

        // then
        assertThat(loginUser).isEqualTo(new LoginUser(7L, user.getUuid(), EMAIL, "드롭헌터", UserRole.USER));
        then(passwordEncoder).should(never()).matches(anyString(), eq(DUMMY_HASH));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS")
    void rejectsWrongPassword() {
        // given
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        given(passwordEncoder.matches(WRONG_PASSWORD, PASSWORD_HASH)).willReturn(false);

        // when & then
        assertFailsWith(WRONG_PASSWORD, UserErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("회원이 없어도 더미 해시로 검증을 한 번 실행한 뒤 INVALID_CREDENTIALS")
    void verifiesDummyHashWhenUserNotFound() {
        // given
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        // when & then
        assertFailsWith(PASSWORD, UserErrorCode.INVALID_CREDENTIALS);
        then(passwordEncoder).should(times(1)).matches(PASSWORD, DUMMY_HASH);
    }

    @Test
    @DisplayName("소셜 회원(해시 없음)도 더미 해시로 검증하고 INVALID_CREDENTIALS이며 null 해시를 넘기지 않는다")
    void verifiesDummyHashForSocialUser() {
        // given
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(socialUser()));

        // when & then
        assertFailsWith(PASSWORD, UserErrorCode.INVALID_CREDENTIALS);
        then(passwordEncoder).should(times(1)).matches(PASSWORD, DUMMY_HASH);
        then(passwordEncoder).should(never()).matches(anyString(), isNull());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("비밀번호가 맞은 비활성 계정은 ACCOUNT_DISABLED")
    void rejectsDisabledAccountWithCorrectPassword(UserStatus status) {
        // given
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(status)));
        given(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).willReturn(true);

        // when & then
        assertFailsWith(PASSWORD, UserErrorCode.ACCOUNT_DISABLED);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("비밀번호가 틀리면 비활성 계정이어도 상태를 드러내지 않고 INVALID_CREDENTIALS")
    void hidesDisabledStatusWithWrongPassword(UserStatus status) {
        // given
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(status)));
        given(passwordEncoder.matches(WRONG_PASSWORD, PASSWORD_HASH)).willReturn(false);

        // when & then
        assertFailsWith(WRONG_PASSWORD, UserErrorCode.INVALID_CREDENTIALS);
    }
}
