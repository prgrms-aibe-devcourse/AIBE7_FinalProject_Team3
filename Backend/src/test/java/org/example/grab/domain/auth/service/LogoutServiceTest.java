package org.example.grab.domain.auth.service;

import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// GR-36 M02-01: 로그아웃의 입력 처리(M00-01), 조회·폐기 분기(M00-02), Redis 오류 전파(M00-03). 저장소는 mock이다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class LogoutServiceTest {

    private static final String TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final UUID SESSION_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");
    private static final RefreshTokenSession SESSION =
            new RefreshTokenSession(7L, SESSION_ID, Instant.parse("2026-11-11T06:00:00Z"));

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LogoutService service;

    // 테스트 출력에는 다른 스레드의 로그도 섞이므로 이 서비스의 로그 줄만 본다
    private static List<String> logoutLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("로그아웃")).toList();
    }

    @Test
    @DisplayName("현재 토큰이면 그 세션을 폐기하고 표식 기반 폐기는 부르지 않는다")
    void revokesSessionOfCurrentToken() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.of(SESSION));

        // when
        service.logout(TOKEN);

        // then
        then(refreshTokenRepository).should().revokeSession(SESSION_ID);
        then(refreshTokenRepository).should(never()).revokeSessionByUsedToken(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("쿠키가 없거나 빈 값·공백이면 저장소를 호출하지 않고 예외 없이 끝난다")
    void skipsRepositoryWithoutToken(String token) {
        // when & then
        assertThatCode(() -> service.logout(token)).doesNotThrowAnyException();
        verifyNoInteractions(refreshTokenRepository);
    }

    // M00-02: 탈취된 토큰으로 먼저 재발급돼도 사용자의 로그아웃으로 세션을 끊는다
    @Test
    @DisplayName("이미 교체된 토큰이면 사용됨 표식으로 세션을 폐기한다")
    void revokesSessionByUsedMarkerWhenTokenWasRotated() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.revokeSessionByUsedToken(TOKEN)).willReturn(Optional.of(SESSION_ID));

        // when
        service.logout(TOKEN);

        // then
        then(refreshTokenRepository).should().revokeSessionByUsedToken(TOKEN);
        then(refreshTokenRepository).should(never()).revokeSession(any());
    }

    @Test
    @DisplayName("만료·이미 로그아웃·발급한 적 없는 토큰이면 폐기할 세션 없이 예외 없이 끝난다")
    void endsQuietlyForUnknownToken() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.revokeSessionByUsedToken(TOKEN)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> service.logout(TOKEN)).doesNotThrowAnyException();
        then(refreshTokenRepository).should(never()).revokeSession(any());
    }

    // M00-03: 폐기를 확인하지 못했는데 성공으로 끝나면 쿠키만 지워지고 토큰이 남는다
    @Test
    @DisplayName("조회 중 Redis 오류는 감싸지 않고 전파하며 폐기를 부르지 않는다")
    void propagatesRedisFailureOnFind() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        // when & then
        assertThatThrownBy(() -> service.logout(TOKEN)).isInstanceOf(RedisConnectionFailureException.class);
        then(refreshTokenRepository).should(never()).revokeSession(any());
        then(refreshTokenRepository).should(never()).revokeSessionByUsedToken(anyString());
    }

    @Test
    @DisplayName("세션 폐기 중 Redis 오류는 감싸지 않고 전파한다")
    void propagatesRedisFailureOnRevokeSession() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.of(SESSION));
        willThrow(new RedisConnectionFailureException("Redis 연결 실패"))
                .given(refreshTokenRepository).revokeSession(SESSION_ID);

        // when & then
        assertThatThrownBy(() -> service.logout(TOKEN)).isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("표식 기반 폐기 중 Redis 오류는 감싸지 않고 전파한다")
    void propagatesRedisFailureOnRevokeByUsedToken() {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.revokeSessionByUsedToken(TOKEN))
                .willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        // when & then
        assertThatThrownBy(() -> service.logout(TOKEN)).isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("로그에는 사유·sessionId만 남고 토큰 원문은 없다")
    void logsWithoutToken(CapturedOutput output) {
        // given
        given(refreshTokenRepository.find(TOKEN)).willReturn(Optional.empty());
        given(refreshTokenRepository.revokeSessionByUsedToken(TOKEN)).willReturn(Optional.of(SESSION_ID));

        // when
        service.logout(TOKEN);

        // then
        assertThat(logoutLines(output)).singleElement()
                .asString()
                .contains("reason=rotated_token", SESSION_ID.toString())
                .doesNotContain(TOKEN);
    }
}
