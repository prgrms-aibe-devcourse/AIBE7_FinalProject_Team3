package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// GR-129 M05-02: 닉네임 수정의 판정 순서(상태 → 같은 값 → 중복 → 저장)와 유니크 위반 변환(M00-05). 저장소는 mock이다
@ExtendWith(MockitoExtension.class)
class UserMeCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final String NICKNAME = "드롭헌터";
    private static final Set<AuthRole> ROLES = Set.of(AuthRole.USER);

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserMeCommandService service;

    private static User user(UserStatus status) {
        User user = User.createLocal("user@example.com", "$argon2id$hash", NICKNAME);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        SQLException sqlException = new SQLException("duplicate key value violates unique constraint", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException, constraintName));
    }

    @Test
    @DisplayName("다른 닉네임으로 바꾸고 내 정보 조회와 같은 응답을 돌려준다")
    void changesNickname() {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByNicknameIgnoreCaseExcludingUser("새닉네임", USER_ID)).willReturn(false);

        // when
        UserMeResponse response = service.changeNickname(USER_ID, EnumSet.of(AuthRole.SELLER, AuthRole.USER), "새닉네임");

        // then: roles는 넘겨받은 권한을 선언 순서로 담는다(M00-02)
        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(response.userId()).isEqualTo(user.getUuid());
        assertThat(response.roles()).containsExactly("USER", "SELLER");
        then(userRepository).should().flush();
    }

    @Test
    @DisplayName("본인 현재 닉네임과 대소문자만 다르면 변경으로 보고 바꾼다")
    void allowsCaseOnlyChange() {
        // given
        User user = user(UserStatus.ACTIVE);
        user.changeNickname("Grab_1");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByNicknameIgnoreCaseExcludingUser("grab_1", USER_ID)).willReturn(false);

        // when
        service.changeNickname(USER_ID, ROLES, "grab_1");

        // then
        assertThat(user.getNickname()).isEqualTo("grab_1");
    }

    @Test
    @DisplayName("현재 닉네임과 대소문자까지 같으면 NICKNAME_UNCHANGED이고 중복 검사·저장을 하지 않는다")
    void rejectsUnchangedNickname() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(UserStatus.ACTIVE)));

        // when & then: 앞뒤 공백 제거는 요청 DTO가 하므로 서비스는 제거된 값을 받는다
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, NICKNAME))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.NICKNAME_UNCHANGED);
        then(userRepository).should(never()).existsByNicknameIgnoreCaseExcludingUser(anyString(), anyLong());
        then(userRepository).should(never()).flush();
    }

    @Test
    @DisplayName("다른 회원과 중복이면 DUPLICATE_NICKNAME이고 닉네임을 바꾸지 않는다")
    void rejectsDuplicateNickname() {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByNicknameIgnoreCaseExcludingUser("GRAB", USER_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, "GRAB"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.DUPLICATE_NICKNAME);
        assertThat(user.getNickname()).isEqualTo(NICKNAME);
        then(userRepository).should(never()).flush();
    }

    @Test
    @DisplayName("동시 변경으로 닉네임 유니크 인덱스에 걸리면 DUPLICATE_NICKNAME으로 바꾸고 원인을 연결하지 않는다")
    void convertsNicknameUniqueViolation() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        given(userRepository.existsByNicknameIgnoreCaseExcludingUser("새닉네임", USER_ID)).willReturn(false);
        willThrow(violation("uq_users_nickname_lower")).given(userRepository).flush();

        // when & then
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, "새닉네임"))
                .isInstanceOf(BusinessException.class)
                .hasNoCause()
                .extracting("errorCode").isEqualTo(UserErrorCode.DUPLICATE_NICKNAME);
    }

    @Test
    @DisplayName("닉네임 유니크 인덱스가 아닌 무결성 위반은 중복으로 숨기지 않고 그대로 던진다")
    void rethrowsOtherIntegrityViolation() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        given(userRepository.existsByNicknameIgnoreCaseExcludingUser("새닉네임", USER_ID)).willReturn(false);
        DataIntegrityViolationException failure = violation("ck_users_local_password");
        willThrow(failure).given(userRepository).flush();

        // when & then
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, "새닉네임")).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지·탈퇴 회원은 내 정보 조회와 같이 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsInactiveUser(UserStatus status) {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(status)));

        // when & then
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, "새닉네임"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED);
        then(userRepository).should(never()).flush();
    }

    @Test
    @DisplayName("회원이 없으면 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsMissingUser() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.changeNickname(USER_ID, ROLES, "새닉네임"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
