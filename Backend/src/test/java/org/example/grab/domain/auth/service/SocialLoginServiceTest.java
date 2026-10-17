package org.example.grab.domain.auth.service;

import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.dto.internal.SocialLoginOutcome;
import org.example.grab.domain.auth.support.oauth2.SocialProfile;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.service.SocialAccountService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// GR-46 M02-01(M00-05): 콜백 판정 3분기(기존 회원·이메일 충돌·가입 필요)와 비활성, 가입 완료의 발급. 협력 객체는 mock이다
@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    private static final String SUB = "4021973658";
    private static final String EMAIL = "user@example.com";
    private static final String SIGNUP_TOKEN = "Qp7_mR2bW9xN4cT1vY8zL3kH6sD0fJ5gA2eU7iO4nBt";
    private static final SocialProfile PROFILE = new SocialProfile(AuthProvider.KAKAO, SUB, EMAIL);
    private static final LoginUser USER = new LoginUser(
            7L, UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8"), EMAIL, "드롭헌터", UserRole.USER);
    private static final LoginResult LOGIN_RESULT = new LoginResult(USER, Set.of(AuthRole.USER),
            new IssuedAccessToken("eyJhbGciOiJIUzI1NiJ9.payload.signature", Instant.parse("2026-10-16T06:15:00Z"), Duration.ofSeconds(899)),
            "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo", Duration.ofDays(14));

    @Mock
    private SocialAccountService socialAccountService;
    @Mock
    private AuthSessionIssuer authSessionIssuer;

    private SocialLoginService service;

    @BeforeEach
    void setUp() {
        service = new SocialLoginService(socialAccountService, authSessionIssuer);
    }

    @Test
    @DisplayName("제공자 식별자로 기존 회원이 있으면 세션을 발급해 로그인시키고, 이메일 충돌 확인·가입 컨텍스트 발급은 하지 않는다")
    void logsInExistingMember() {
        // given
        given(socialAccountService.findLoginUser(AuthProvider.KAKAO, SUB)).willReturn(Optional.of(USER));
        given(authSessionIssuer.issue(USER)).willReturn(LOGIN_RESULT);

        // when
        SocialLoginOutcome outcome = service.login(PROFILE);

        // then
        assertThat(outcome.isSignupRequired()).isFalse();
        assertThat(outcome.loginResult()).isEqualTo(LOGIN_RESULT);
        then(socialAccountService).should(never()).requireEmailNotTaken(anyString());
        then(socialAccountService).should(never()).issueSignupContext(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("기존 회원이 비활성이면 ACCOUNT_DISABLED로 끝나고 세션을 발급하지 않는다")
    void rejectsDisabledMember() {
        // given
        given(socialAccountService.findLoginUser(AuthProvider.KAKAO, SUB))
                .willThrow(new BusinessException(UserErrorCode.ACCOUNT_DISABLED));

        // when & then
        assertThatThrownBy(() -> service.login(PROFILE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ACCOUNT_DISABLED));
        then(authSessionIssuer).shouldHaveNoInteractions();
        then(socialAccountService).should(never()).issueSignupContext(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("기존 회원이 없고 같은 이메일 회원이 있으면 ACCOUNT_LINK_REQUIRED로 끝나고 가입 컨텍스트를 만들지 않는다")
    void rejectsEmailConflict() {
        // given
        given(socialAccountService.findLoginUser(AuthProvider.KAKAO, SUB)).willReturn(Optional.empty());
        willThrow(new BusinessException(UserErrorCode.ACCOUNT_LINK_REQUIRED))
                .given(socialAccountService).requireEmailNotTaken(EMAIL);

        // when & then
        assertThatThrownBy(() -> service.login(PROFILE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ACCOUNT_LINK_REQUIRED));
        then(socialAccountService).should(never()).issueSignupContext(any(), anyString(), anyString());
        then(authSessionIssuer).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("기존 회원도 같은 이메일 회원도 없으면 프로필 값으로 가입 컨텍스트를 발급하고 세션은 발급하지 않는다")
    void requiresSignupForNewMember() {
        // given
        given(socialAccountService.findLoginUser(AuthProvider.KAKAO, SUB)).willReturn(Optional.empty());
        given(socialAccountService.issueSignupContext(AuthProvider.KAKAO, SUB, EMAIL)).willReturn(SIGNUP_TOKEN);

        // when
        SocialLoginOutcome outcome = service.login(PROFILE);

        // then
        assertThat(outcome.isSignupRequired()).isTrue();
        assertThat(outcome.signupToken()).isEqualTo(SIGNUP_TOKEN);
        assertThat(outcome.loginResult()).isNull();
        then(socialAccountService).should().requireEmailNotTaken(EMAIL);
        then(authSessionIssuer).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("가입 완료는 회원을 만든 뒤 LOCAL 로그인과 같은 발급기로 세션을 발급한다")
    void issuesSessionAfterSignup() {
        // given
        given(socialAccountService.signup(SIGNUP_TOKEN, "드롭헌터")).willReturn(USER);
        given(authSessionIssuer.issue(USER)).willReturn(LOGIN_RESULT);

        // when & then
        assertThat(service.signup(SIGNUP_TOKEN, "드롭헌터")).isEqualTo(LOGIN_RESULT);
    }

    @Test
    @DisplayName("판정 결과의 toString은 가입 토큰과 Refresh Token 원문을 드러내지 않는다")
    void masksTokensInOutcomeToString() {
        assertThat(SocialLoginOutcome.signupRequired(SIGNUP_TOKEN).toString()).doesNotContain(SIGNUP_TOKEN);
        assertThat(SocialLoginOutcome.loggedIn(LOGIN_RESULT).toString()).doesNotContain(LOGIN_RESULT.refreshToken());
    }

    @Test
    @DisplayName("판정 결과는 로그인 결과와 가입 토큰 중 정확히 하나만 가진다")
    void outcomeHasExactlyOneValue() {
        assertThatThrownBy(() -> new SocialLoginOutcome(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocialLoginOutcome(LOGIN_RESULT, SIGNUP_TOKEN)).isInstanceOf(IllegalArgumentException.class);
    }
}
