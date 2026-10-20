package org.example.grab.domain.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.grab.domain.auth.dto.internal.TokenRefreshResult;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenReuseCheck;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.AuthRoleCalculator;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.service.ActiveUserLookupService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// GR-35 M02-01: 재발급 흐름의 순서, 세션 이어받기, 실패 사유별 처리와 로그 수준, 결과의 토큰 가림. 협력 객체는 모두 mock이다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TokenRefreshServiceTest {

    private static final String OLD_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final String NEW_TOKEN = "Pq8_nB2cX5vZ1mK7lJ4hG9fD3sA6wE0rT2yU8iO1pLk";
    private static final UUID PUBLIC_ID = UUID.fromString("6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8");
    private static final UUID SESSION_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");
    private static final RefreshTokenSession SESSION =
            new RefreshTokenSession(7L, SESSION_ID, Instant.parse("2026-11-11T06:00:00Z"));
    private static final LoginUser USER = new LoginUser(7L, PUBLIC_ID, "user@example.com", "드롭헌터", UserRole.USER);
    private static final Set<AuthRole> ROLES = Set.of(AuthRole.USER, AuthRole.SELLER);
    private static final IssuedAccessToken ACCESS_TOKEN = new IssuedAccessToken(
            "eyJhbGciOiJIUzI1NiJ9.payload.signature", Instant.parse("2026-10-13T06:15:00Z"), Duration.ofSeconds(899));
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private ActiveUserLookupService activeUserLookupService;
    @Mock
    private AuthRoleCalculator authRoleCalculator;
    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @InjectMocks
    private TokenRefreshService service;

    private void givenIssuable() {
        given(refreshTokenRepository.find(OLD_TOKEN)).willReturn(Optional.of(SESSION));
        given(activeUserLookupService.findActive(7L)).willReturn(Optional.of(USER));
        given(authRoleCalculator.calculate(PUBLIC_ID, UserRole.USER)).willReturn(ROLES);
        given(jwtProvider.issue(PUBLIC_ID, ROLES)).willReturn(ACCESS_TOKEN);
        given(refreshTokenGenerator.generate()).willReturn(NEW_TOKEN);
    }

    // 테스트 출력에는 다른 스레드(Redis 클라이언트 등)의 로그도 섞이므로 이 서비스의 거부 로그 줄만 본다
    private static List<String> rejectionLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("토큰 재발급 거부")).toList();
    }

    private static void assertInvalidToken(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("조회 → 회원 확인 → roles → Access Token → 교체 순서로 처리하고, 결과에 새 토큰과 각 쿠키 Max-Age를 담는다")
    void refreshesInOrder() {
        // given
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION)).willReturn(Optional.of(REFRESH_TTL));

        // when
        TokenRefreshResult result = service.refresh(OLD_TOKEN);

        // then
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(NEW_TOKEN);
        assertThat(result.refreshTokenMaxAge()).isEqualTo(REFRESH_TTL);

        InOrder order = inOrder(refreshTokenRepository, activeUserLookupService, authRoleCalculator, jwtProvider,
                refreshTokenGenerator);
        order.verify(refreshTokenRepository).find(OLD_TOKEN);
        order.verify(activeUserLookupService).findActive(7L);
        order.verify(authRoleCalculator).calculate(PUBLIC_ID, UserRole.USER);
        order.verify(jwtProvider).issue(PUBLIC_ID, ROLES);
        order.verify(refreshTokenGenerator).generate();
        order.verify(refreshTokenRepository).rotate(OLD_TOKEN, NEW_TOKEN, SESSION);
    }

    // 세션을 이어받아야 재발급을 반복해도 절대 만료가 늘지 않는다
    @Test
    @DisplayName("교체에는 옛 토큰의 세션(sessionId·sessionExpiresAt)을 그대로 넘긴다")
    void inheritsSession() {
        // given
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION)).willReturn(Optional.of(REFRESH_TTL));

        // when
        service.refresh(OLD_TOKEN);

        // then
        then(refreshTokenRepository).should().rotate(OLD_TOKEN, NEW_TOKEN, SESSION);
        then(refreshTokenRepository).should(never()).save(anyString(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("쿠키가 없거나 비었으면 DEBUG로만 기록하고 Redis 조회 없이 INVALID_TOKEN이다")
    void rejectsMissingCookie(String rawToken) {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(TokenRefreshService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            // when & then
            assertInvalidToken(() -> service.refresh(rawToken));
            verifyNoInteractions(refreshTokenRepository, activeUserLookupService, jwtProvider);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("reason=missing_cookie");
            });
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("없는 토큰이고 교체된 적도 없으면 INVALID_TOKEN이고 세션을 건드리지 않는다")
    void rejectsUnknownToken(CapturedOutput output) {
        // given
        given(refreshTokenRepository.find(OLD_TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.checkReuse(OLD_TOKEN))
                .willReturn(new RefreshTokenReuseCheck(RefreshTokenReuseCheck.Status.NOT_FOUND, null));

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        then(refreshTokenRepository).should(never()).revokeSession(any());
        verifyNoInteractions(activeUserLookupService, jwtProvider);
        assertThat(output).contains("reason=not_found");
    }

    @Test
    @DisplayName("유예 안의 재사용은 INFO로 남기고 INVALID_TOKEN이다")
    void rejectsReuseWithinGrace(CapturedOutput output) {
        // given
        given(refreshTokenRepository.find(OLD_TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.checkReuse(OLD_TOKEN))
                .willReturn(new RefreshTokenReuseCheck(RefreshTokenReuseCheck.Status.WITHIN_GRACE, SESSION_ID));

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        verifyNoInteractions(jwtProvider);
        assertThat(rejectionLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains(" INFO ", "reason=reuse_within_grace", SESSION_ID.toString()));
    }

    // M00-07: 세션을 폐기한 탈취 의심만 WARN이다
    @Test
    @DisplayName("유예가 지난 재사용은 WARN으로 남기고 INVALID_TOKEN이다")
    void rejectsDetectedReuseWithWarning(CapturedOutput output) {
        // given
        given(refreshTokenRepository.find(OLD_TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.checkReuse(OLD_TOKEN))
                .willReturn(new RefreshTokenReuseCheck(RefreshTokenReuseCheck.Status.REUSE_DETECTED, SESSION_ID));

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        verifyNoInteractions(jwtProvider);
        assertThat(rejectionLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains(" WARN ", "reason=reuse_detected", SESSION_ID.toString()));
    }

    // M00-04: 로그인 후 정지·탈퇴한 회원은 재발급할 수 없고, 남은 세션도 지운다
    @Test
    @DisplayName("회원이 없거나 활성이 아니면 세션을 폐기하고 INVALID_TOKEN이며 토큰을 발급하지 않는다")
    void revokesSessionOfInactiveUser(CapturedOutput output) {
        // given
        given(refreshTokenRepository.find(OLD_TOKEN)).willReturn(Optional.of(SESSION));
        given(activeUserLookupService.findActive(7L)).willReturn(Optional.empty());

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        then(refreshTokenRepository).should().revokeSession(SESSION_ID);
        then(refreshTokenRepository).should(never()).rotate(anyString(), anyString(), any());
        verifyNoInteractions(jwtProvider, refreshTokenGenerator);
        assertThat(output).contains("reason=user_inactive");
    }

    @Test
    @DisplayName("다른 요청이 먼저 교체해 옛 토큰이 없으면 INVALID_TOKEN이다")
    void rejectsWhenRaceLost(CapturedOutput output) {
        // given
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION)).willReturn(Optional.empty());

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        assertThat(output).contains("reason=race_lost");
    }

    @Test
    @DisplayName("절대 만료까지 1초 미만이라 교체가 거부되면 사유를 남기고 INVALID_TOKEN을 그대로 던진다")
    void rejectsExpiringSession(CapturedOutput output) {
        // given
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION))
                .willThrow(new BusinessException(CommonErrorCode.INVALID_TOKEN));

        // when & then
        assertInvalidToken(() -> service.refresh(OLD_TOKEN));
        assertThat(output).contains("reason=session_expired");
    }

    // GR-33 M03-03: Redis 장애를 INVALID_TOKEN으로 바꾸면 장애 동안 재발급한 사용자가 전부 로그아웃된다
    @Test
    @DisplayName("Redis 오류는 감싸지 않고 그대로 전파된다")
    void propagatesRedisFailure() {
        // given
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis 연결 실패");
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION)).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.refresh(OLD_TOKEN)).isSameAs(failure);
    }

    @Test
    @DisplayName("성공·실패 로그와 결과의 toString()에 토큰 원문이 없다")
    void neverExposesRawTokens(CapturedOutput output) {
        // given
        givenIssuable();
        given(refreshTokenRepository.rotate(OLD_TOKEN, NEW_TOKEN, SESSION)).willReturn(Optional.of(REFRESH_TTL));

        // when
        TokenRefreshResult result = service.refresh(OLD_TOKEN);

        // then
        assertThat(result.toString()).doesNotContain(NEW_TOKEN, ACCESS_TOKEN.value()).contains("refreshToken=masked");
        assertThat(output).contains("토큰 재발급 성공").contains(PUBLIC_ID.toString())
                .doesNotContain(OLD_TOKEN, NEW_TOKEN, ACCESS_TOKEN.value());
    }
}
