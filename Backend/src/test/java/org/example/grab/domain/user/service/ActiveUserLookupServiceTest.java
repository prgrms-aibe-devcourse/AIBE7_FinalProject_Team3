package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

// GR-35 M02-01: 재발급의 회원 재조회. 활성 회원만 돌려주고, 없음과 비활성은 구분하지 않는다(M00-04)
@ExtendWith(MockitoExtension.class)
class ActiveUserLookupServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ActiveUserLookupService service;

    private static User user(UserStatus status) {
        User user = User.createLocal("user@example.com", "$argon2id$hash", "드롭헌터");
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    @Test
    @DisplayName("활성 회원이면 내부 ID·public_id·역할을 담은 LoginUser를 돌려준다")
    void returnsActiveUser() {
        // given
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        // when
        Optional<LoginUser> result = service.findActive(7L);

        // then
        assertThat(result).contains(LoginUser.from(user));
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("활성이 아닌 회원은 빈 결과다")
    void hidesInactiveUser(UserStatus status) {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(user(status)));

        // when & then
        assertThat(service.findActive(7L)).isEmpty();
    }

    @Test
    @DisplayName("회원이 없으면 빈 결과다")
    void returnsEmptyForMissingUser() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        // when & then
        assertThat(service.findActive(7L)).isEmpty();
    }
}
