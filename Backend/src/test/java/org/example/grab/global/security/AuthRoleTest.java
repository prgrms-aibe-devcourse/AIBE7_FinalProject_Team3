package org.example.grab.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// GR-97 M02-01: Spring Security 권한 문자열과 AuthRole의 왕복 변환. 내 정보 조회의 roles가 이 변환을 쓴다
class AuthRoleTest {

    @Test
    @DisplayName("권한 문자열은 ROLE_ 접두사에 이름을 붙인 값이다")
    void authorityHasRolePrefix() {
        assertThat(AuthRole.USER.authority()).isEqualTo("ROLE_USER");
        assertThat(AuthRole.SELLER.authority()).isEqualTo("ROLE_SELLER");
        assertThat(AuthRole.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    @DisplayName("authority()로 만든 문자열은 fromAuthority로 같은 권한이 된다")
    void fromAuthorityReversesAuthority(AuthRole role) {
        assertThat(AuthRole.fromAuthority(role.authority())).contains(role);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"USER", "role_user", "ROLE_UNKNOWN", "ROLE_ANONYMOUS", "FACTOR_PASSWORD"})
    @DisplayName("이 enum이 만든 권한 문자열이 아니면 빈 결과다")
    void fromAuthorityIgnoresOtherAuthorities(String authority) {
        assertThat(AuthRole.fromAuthority(authority)).isEmpty();
    }
}
