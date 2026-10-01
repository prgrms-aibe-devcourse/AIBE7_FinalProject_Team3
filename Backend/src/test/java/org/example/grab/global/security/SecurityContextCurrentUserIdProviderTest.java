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

// U4: principal의 public_id를 UserIdResolver로 users.id로 바꾼다(GR-32 M03-05-1)
class SecurityContextCurrentUserIdProviderTest {

    private static final UUID PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    // 등록된 회원 하나만 아는 가짜 Resolver
    private final UserIdResolver resolver = publicId -> Optional.ofNullable(Map.of(PUBLIC_ID, 7L).get(publicId));
    private final SecurityContextCurrentUserIdProvider provider = new SecurityContextCurrentUserIdProvider(resolver);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("principal의 public_id에 해당하는 users.id를 돌려준다")
    void returnsInternalIdOfPrincipal() {
        // given
        authenticate(new AccessTokenPrincipal(PUBLIC_ID));

        // when, then
        assertThat(provider.currentUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("public_id에 해당하는 회원이 없으면 AUTHENTICATION_REQUIRED")
    void rejectsUnknownPublicId() {
        // given
        authenticate(new AccessTokenPrincipal(UUID.randomUUID()));

        // when, then
        assertErrorCode(CommonErrorCode.AUTHENTICATION_REQUIRED);
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
    @DisplayName("principal이 AuthenticatedUser가 아니면 AUTHENTICATION_REQUIRED")
    void rejectsOtherPrincipal() {
        // given
        authenticate("user");

        // when, then
        assertErrorCode(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private void assertErrorCode(CommonErrorCode errorCode) {
        assertThatThrownBy(provider::currentUserId)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
