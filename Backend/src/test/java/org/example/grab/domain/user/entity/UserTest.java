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
}
