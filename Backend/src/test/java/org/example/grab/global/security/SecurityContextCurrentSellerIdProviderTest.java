package org.example.grab.global.security;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityContextCurrentSellerIdProviderTest {

    private final SecurityContextCurrentSellerIdProvider provider = new SecurityContextCurrentSellerIdProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증 정보가 없으면 AUTHENTICATION_REQUIRED")
    void rejectsAnonymous() {
        assertThatThrownBy(provider::currentSellerId)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("SELLER 권한이 없으면 ACCESS_DENIED")
    void rejectsNonSeller() {
        authenticate("ROLE_USER", "user");

        assertThatThrownBy(provider::currentSellerId)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("SELLER 권한이지만 principal에 sellerId가 없으면 AUTHENTICATION_REQUIRED")
    void rejectsSellerWithoutPrincipalContract() {
        authenticate("ROLE_SELLER", "user");

        assertThatThrownBy(provider::currentSellerId)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("SELLER 권한이고 principal이 sellerId를 제공하면 반환한다")
    void returnsSellerId() {
        AuthenticatedSeller principal = () -> 5L;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_SELLER"))));

        assertThat(provider.currentSellerId()).isEqualTo(5L);
    }

    private void authenticate(String authority, Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }
}
