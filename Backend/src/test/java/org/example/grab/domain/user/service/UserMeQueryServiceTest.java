package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// GR-97 M02-01: 내 정보 조회의 필드 매핑(GR-108 provider 포함), roles 정렬, 회원 없음·비활성 거부(M00-03). 저장소는 mock이다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class UserMeQueryServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-18T14:00:00+09:00");

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserMeQueryService service;

    private static User user(UserStatus status) {
        User user = User.createLocal(EMAIL, "$argon2id$hash", "드롭헌터");
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "createdAt", CREATED_AT);
        return user;
    }

    @Test
    @DisplayName("활성 회원이면 public_id·이메일·닉네임·가입 방식·roles·프로필 이미지(null)·가입 시각을 돌려준다")
    void returnsActiveUser() {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        // when
        UserMeResponse response = service.findMe(7L, Set.of(AuthRole.USER));

        // then
        assertThat(response).isEqualTo(new UserMeResponse(
                user.getUuid(), EMAIL, "드롭헌터", AuthProvider.LOCAL, List.of("USER"), null, CREATED_AT));
    }

    // GR-108: 가입 방식은 저장된 값 그대로다
    @Test
    @DisplayName("소셜 회원이면 provider에 저장된 가입 방식을 그대로 담는다")
    void returnsSocialProvider() {
        // given
        User user = user(UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "provider", AuthProvider.KAKAO);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        // when
        UserMeResponse response = service.findMe(7L, Set.of(AuthRole.USER));

        // then
        assertThat(response.provider()).isEqualTo(AuthProvider.KAKAO);
    }

    // 로그인 응답·JWT Claim과 같은 AuthRole 선언 순서(USER, SELLER, ADMIN)
    @Test
    @DisplayName("roles는 넘겨받은 권한을 AuthRole 선언 순서로 담는다")
    void sortsRolesInDeclarationOrder() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(user(UserStatus.ACTIVE)));

        // when
        UserMeResponse response = service.findMe(7L, EnumSet.of(AuthRole.ADMIN, AuthRole.SELLER, AuthRole.USER));

        // then
        assertThat(response.roles()).containsExactly("USER", "SELLER", "ADMIN");
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("정지·탈퇴 회원은 회원 없음과 같은 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsInactiveUser(UserStatus status) {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(user(status)));

        // when & then
        assertThatThrownBy(() -> service.findMe(7L, Set.of(AuthRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    @DisplayName("회원이 없으면 AUTHENTICATION_REQUIRED로 거부한다")
    void rejectsMissingUser() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.findMe(7L, Set.of(AuthRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    @DisplayName("거부 로그에는 사유와 내부 ID만 남고 이메일은 없다")
    void rejectionLogOmitsEmail(CapturedOutput output) {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(user(UserStatus.SUSPENDED)));

        // when
        assertThatThrownBy(() -> service.findMe(7L, Set.of(AuthRole.USER))).isInstanceOf(BusinessException.class);

        // then
        assertThat(output.getAll().lines().filter(line -> line.contains("내 정보 조회 거부")).toList())
                .singleElement().asString()
                .contains("reason=user_inactive", "userId=7")
                .doesNotContain(EMAIL);
    }
}
