package org.example.grab.domain.auth.service;

import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.AuthRoleCalculator;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

// GR-46 M02-01(M00-08): LOCAL·소셜이 함께 쓰는 세션 발급기의 순서, 새 세션 저장 값, 저장 실패 전파. 협력 객체는 모두 mock이다
@ExtendWith(MockitoExtension.class)
class AuthSessionIssuerTest {

    private static final UUID PUBLIC_ID = UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8");
    private static final LoginUser USER = new LoginUser(7L, PUBLIC_ID, "user@example.com", "드롭헌터", UserRole.USER);
    private static final Set<AuthRole> ROLES = Set.of(AuthRole.USER);
    private static final IssuedAccessToken ACCESS_TOKEN = new IssuedAccessToken(
            "eyJhbGciOiJIUzI1NiJ9.payload.signature", Instant.parse("2026-10-16T06:15:00Z"), Duration.ofSeconds(899));
    private static final String REFRESH_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final Instant SESSION_EXPIRES_AT = Instant.parse("2026-11-15T06:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

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

    private AuthSessionIssuer issuer;

    @BeforeEach
    void setUp() {
        issuer = new AuthSessionIssuer(
                authRoleCalculator, jwtProvider, refreshTokenGenerator, refreshTokenTtlCalculator, refreshTokenRepository);
        given(authRoleCalculator.calculate(PUBLIC_ID, UserRole.USER)).willReturn(ROLES);
        given(jwtProvider.issue(PUBLIC_ID, ROLES)).willReturn(ACCESS_TOKEN);
        given(refreshTokenGenerator.generate()).willReturn(REFRESH_TOKEN);
        given(refreshTokenTtlCalculator.newSessionExpiresAt()).willReturn(SESSION_EXPIRES_AT);
    }

    @Test
    @DisplayName("roles → Access Token → Refresh 저장 순서로 발급하고, 결과에 회원·토큰·Refresh Max-Age를 담는다")
    void issuesInOrder() {
        // given
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willReturn(REFRESH_TTL);

        // when
        LoginResult result = issuer.issue(USER);

        // then
        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.roles()).isEqualTo(ROLES);
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(result.refreshTokenMaxAge()).isEqualTo(REFRESH_TTL);

        InOrder order = inOrder(authRoleCalculator, jwtProvider, refreshTokenRepository);
        order.verify(authRoleCalculator).calculate(PUBLIC_ID, UserRole.USER);
        order.verify(jwtProvider).issue(PUBLIC_ID, ROLES);
        order.verify(refreshTokenRepository).save(anyString(), any(RefreshTokenSession.class));
    }

    @Test
    @DisplayName("발급마다 회원 내부 ID와 절대 만료 시각을 담은 새 세션을 RefreshTokenRepository.save로 저장한다")
    void savesNewSessionEachTime() {
        // given
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willReturn(REFRESH_TTL);
        ArgumentCaptor<RefreshTokenSession> session = ArgumentCaptor.forClass(RefreshTokenSession.class);

        // when
        issuer.issue(USER);
        issuer.issue(USER);

        // then: 원문을 넘기고(해시는 저장소가 계산), 발급마다 다른 세션이다(GR-35 인계)
        then(refreshTokenRepository).should(times(2)).save(eq(REFRESH_TOKEN), session.capture());
        RefreshTokenSession first = session.getAllValues().get(0);
        RefreshTokenSession second = session.getAllValues().get(1);
        assertThat(first.userId()).isEqualTo(7L);
        assertThat(first.sessionExpiresAt()).isEqualTo(SESSION_EXPIRES_AT);
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
    }

    @Test
    @DisplayName("Redis 저장 오류는 감싸지 않고 전파해 결과(쿠키)를 만들지 않는다")
    void propagatesRedisFailure() {
        // given
        RedisConnectionFailureException failure = new RedisConnectionFailureException("down");
        given(refreshTokenRepository.save(anyString(), any(RefreshTokenSession.class))).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> issuer.issue(USER)).isSameAs(failure);
    }
}
