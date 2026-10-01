package org.example.grab.global.security;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.jwt.AccessTokenPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// U5: ROLE_SELLER 확인 후 principal의 public_id를 SellerIdResolver로 sellers.id로 바꾼다(GR-32 M03-05-2)
class SecurityContextCurrentSellerIdProviderTest {

    private static final UUID SELLER_PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    // 승인된 판매자 하나만 아는 가짜 Resolver
    private final SellerIdResolver resolver =
            publicId -> Optional.ofNullable(Map.of(SELLER_PUBLIC_ID, 5L).get(publicId));
    private final SecurityContextCurrentSellerIdProvider provider = new SecurityContextCurrentSellerIdProvider(resolver);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증 정보가 없거나 익명이면 AUTHENTICATION_REQUIRED")
    void rejectsMissingOrAnonymousAuthentication() {
        assertErrorCode(CommonErrorCode.AUTHENTICATION_REQUIRED);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertErrorCode(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("SELLER 권한이 없으면 승인된 판매자여도 ACCESS_DENIED")
    void rejectsNonSeller() {
        // given
        authenticate(new AccessTokenPrincipal(SELLER_PUBLIC_ID), "ROLE_USER");

        // when, then
        assertErrorCode(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("SELLER 권한이지만 승인된 판매자로 조회되지 않으면 ACCESS_DENIED")
    void rejectsSellerNotApproved() {
        // given
        // 토큰 발급 뒤 승인이 취소된 경우
        authenticate(new AccessTokenPrincipal(UUID.randomUUID()), "ROLE_USER", "ROLE_SELLER");

        // when, then
        assertErrorCode(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("SELLER 권한이고 승인된 판매자면 sellers.id를 돌려준다")
    void returnsSellerId() {
        // given
        authenticate(new AccessTokenPrincipal(SELLER_PUBLIC_ID), "ROLE_USER", "ROLE_SELLER");

        // when, then
        assertThat(provider.currentSellerId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("SELLER 권한이지만 principal이 AuthenticatedUser가 아니면 AUTHENTICATION_REQUIRED")
    void rejectsOtherPrincipal() {
        // given
        authenticate("user", "ROLE_SELLER");

        // when, then
        assertErrorCode(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static void authenticate(Object principal, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList(authorities)));
    }

    private void assertErrorCode(CommonErrorCode errorCode) {
        assertThatThrownBy(provider::currentSellerId)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
