package org.example.grab.domain.auth.service;

import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.AuthRoleCalculator;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.service.UserCredentialService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

// GR-34 M02-01: 로그인 흐름의 순서, Refresh 세션 저장 값, 실패 시 중단, 결과의 토큰 가림. 협력 객체는 모두 mock이다
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Password123!";
    private static final UUID PUBLIC_ID = UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8");
    private static final LoginUser USER = new LoginUser(7L, PUBLIC_ID, EMAIL, "드롭헌터", UserRole.USER);
    private static final Set<AuthRole> ROLES = Set.of(AuthRole.USER);
    private static final IssuedAccessToken ACCESS_TOKEN = new IssuedAccessToken(
            "eyJhbGciOiJIUzI1NiJ9.payload.signature", Instant.parse("2026-10-12T06:15:00Z"), Duration.ofSeconds(899));
    private static final String REFRESH_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final Instant SESSION_EXPIRES_AT = Instant.parse("2026-11-11T06:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    @Mock
    private UserCredentialService userCredentialService;
    @Mock
    private AuthRoleCalculator authRoleCalculator;
    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;
    @Mock
    private RefreshTokenTtlCalculator refreshTokenTtlCalculator;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LoginService service;

    private void givenLoginPossible() {
        given(userCredentialService.authenticate(EMAIL, PASSWORD)).willReturn(USER);
        given(authRoleCalculator.calculate(PUBLIC_ID, UserRole.USER)).willReturn(ROLES);
        given(jwtProvider.issue(PUBLIC_ID, ROLES)).willReturn(ACCESS_TOKEN);
        given(refreshTokenGenerator.generate()).willReturn(REFRESH_TOKEN);
        given(refreshTokenTtlCalculator.newSessionExpiresAt()).willReturn(SESSION_EXPIRES_AT);
    }

    @Test
    @DisplayName("자격 확인 → roles → Access Token → Refresh 저장 순서로 처리하고, 결과에 토큰과 각 쿠키 Max-Age를 담는다")
    void logsInInOrder() {
        // given
        givenLoginPossible();
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willReturn(REFRESH_TTL);

        // when
        LoginResult result = service.login(EMAIL, PASSWORD);

        // then
        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.roles()).isEqualTo(ROLES);
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(result.refreshTokenMaxAge()).isEqualTo(REFRESH_TTL);

        InOrder order = inOrder(userCredentialService, authRoleCalculator, jwtProvider, refreshTokenRepository);
        order.verify(userCredentialService).authenticate(EMAIL, PASSWORD);
        order.verify(authRoleCalculator).calculate(PUBLIC_ID, UserRole.USER);
        order.verify(jwtProvider).issue(PUBLIC_ID, ROLES);
        order.verify(refreshTokenRepository).save(anyString(), any(RefreshTokenSession.class));
    }

    @Test
    @DisplayName("Refresh 세션에는 회원 내부 ID, 새 sessionId, 로그인 시점의 절대 만료 시각을 저장한다")
    void savesNewSession() {
        // given
        givenLoginPossible();
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willReturn(REFRESH_TTL);
        ArgumentCaptor<RefreshTokenSession> session = ArgumentCaptor.forClass(RefreshTokenSession.class);

        // when
        service.login(EMAIL, PASSWORD);
        service.login(EMAIL, PASSWORD);

        // then: 원문을 넘기고(해시는 저장소가 계산), 로그인마다 다른 세션이다
        then(refreshTokenRepository).should(times(2))
                .save(eq(REFRESH_TOKEN), session.capture());
        RefreshTokenSession first = session.getAllValues().get(0);
        RefreshTokenSession second = session.getAllValues().get(1);
        assertThat(first.userId()).isEqualTo(7L);
        assertThat(first.sessionExpiresAt()).isEqualTo(SESSION_EXPIRES_AT);
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
    }

    @Test
    @DisplayName("자격 확인이 실패하면 토큰을 발급하지 않고 Redis에 저장하지 않는다")
    void stopsWhenAuthenticationFails() {
        // given
        given(userCredentialService.authenticate(EMAIL, PASSWORD))
                .willThrow(new BusinessException(UserErrorCode.INVALID_CREDENTIALS));

        // when & then
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD)).isInstanceOf(BusinessException.class);
        then(jwtProvider).shouldHaveNoInteractions();
        then(refreshTokenGenerator).shouldHaveNoInteractions();
        then(refreshTokenRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Redis 저장 오류는 감싸지 않고 전파해 결과(쿠키)를 만들지 않는다")
    void propagatesRedisFailure() {
        // given
        givenLoginPossible();
        RedisConnectionFailureException failure = new RedisConnectionFailureException("down");
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD)).isSameAs(failure);
    }

    @Test
    @DisplayName("결과의 toString은 Access·Refresh Token 원문과 이메일을 드러내지 않고 회원은 public_id로만 표시한다")
    void masksTokensInResultToString() {
        // given
        LoginResult result = new LoginResult(USER, ROLES, ACCESS_TOKEN, REFRESH_TOKEN, REFRESH_TTL);

        // when
        String text = result.toString();

        // then
        assertThat(text)
                .doesNotContain(REFRESH_TOKEN)
                .doesNotContain(ACCESS_TOKEN.value())
                .doesNotContain(EMAIL)
                .contains("refreshToken=masked")
                .contains(PUBLIC_ID.toString());
    }
}
