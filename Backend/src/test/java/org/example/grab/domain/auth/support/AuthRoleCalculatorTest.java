package org.example.grab.domain.auth.support;

import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.identity.SellerIdResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// GR-34 M02-01: 발급 시점 roles 계산(M00-05). 판매자 승인 여부는 mock으로 정한다
@ExtendWith(MockitoExtension.class)
class AuthRoleCalculatorTest {

    private static final UUID PUBLIC_ID = UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8");

    @Mock
    private SellerIdResolver sellerIdResolver;

    @InjectMocks
    private AuthRoleCalculator calculator;

    @Test
    @DisplayName("일반 회원은 USER 하나다")
    void returnsUserForMember() {
        // given
        given(sellerIdResolver.resolveApproved(PUBLIC_ID)).willReturn(Optional.empty());

        // when & then
        assertThat(calculator.calculate(PUBLIC_ID, UserRole.USER)).containsExactly(AuthRole.USER);
    }

    @Test
    @DisplayName("ADMIN 회원은 USER를 잃지 않고 ADMIN이 추가된다")
    void keepsUserForAdmin() {
        // given
        given(sellerIdResolver.resolveApproved(PUBLIC_ID)).willReturn(Optional.empty());

        // when & then
        assertThat(calculator.calculate(PUBLIC_ID, UserRole.ADMIN)).containsExactly(AuthRole.USER, AuthRole.ADMIN);
    }

    @Test
    @DisplayName("승인된 판매자는 SELLER가 추가되고 순서는 AuthRole 선언 순서(USER, SELLER, ADMIN)다")
    void addsSellerForApprovedSeller() {
        // given
        given(sellerIdResolver.resolveApproved(PUBLIC_ID)).willReturn(Optional.of(3L));

        // when & then
        assertThat(calculator.calculate(PUBLIC_ID, UserRole.USER)).containsExactly(AuthRole.USER, AuthRole.SELLER);
        assertThat(calculator.calculate(PUBLIC_ID, UserRole.ADMIN))
                .containsExactly(AuthRole.USER, AuthRole.SELLER, AuthRole.ADMIN);
    }

    @Test
    @DisplayName("결과는 바꿀 수 없다")
    void returnsUnmodifiableSet() {
        // given
        given(sellerIdResolver.resolveApproved(PUBLIC_ID)).willReturn(Optional.empty());
        Set<AuthRole> roles = calculator.calculate(PUBLIC_ID, UserRole.USER);

        // when & then
        assertThatThrownBy(() -> roles.add(AuthRole.ADMIN)).isInstanceOf(UnsupportedOperationException.class);
    }
}
