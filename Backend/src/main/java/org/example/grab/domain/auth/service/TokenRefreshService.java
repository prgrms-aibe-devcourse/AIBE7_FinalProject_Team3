package org.example.grab.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.dto.internal.TokenRefreshResult;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenReuseCheck;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.AuthRoleCalculator;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.service.ActiveUserLookupService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/*
    토큰 재발급(Refresh Token Rotation, MEMBER_AUTH.md 1.7, GR-35).
    조회 → 회원·권한 확인 → Access Token 발급 → 교체 순서로 처리한다(M00-01).
    교체를 마지막 한 단계로 두어, 그 전에 실패하면(DB 오류 등) 옛 토큰이 그대로 남아 다시 시도할 수 있다.
    교체 스크립트가 성공한 뒤에만 결과를 돌려주므로 서버가 모르는 토큰을 쿠키로 주지 않는다.

    실패는 사유와 관계없이 INVALID_TOKEN 하나로 응답하고, 사유는 로그로만 구분한다(M00-07).
    쿠키 없음은 DEBUG, 탈취 의심(reuse_detected)은 WARN, 만료·여러 탭 경쟁 등 나머지 거부 사유는 INFO다.
    읽을 수 없는 세션 값(malformed)은 저장소가 경고를 남기고 없는 토큰으로 처리한다.
    Redis 오류는 잡지 않아 500이 된다. INVALID_TOKEN으로 바꾸면 Redis 장애 때 전원이 로그아웃된다(GR-33 M03-03).
    서비스 트랜잭션을 두지 않는다. 회원 조회는 끝나면 DB 연결을 돌려주고, Redis 교체는 DB 트랜잭션과 묶이지 않는다.
    토큰 원문·해시는 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final ActiveUserLookupService activeUserLookupService;
    private final AuthRoleCalculator authRoleCalculator;
    private final JwtProvider jwtProvider;
    private final RefreshTokenGenerator refreshTokenGenerator;

    // rawToken은 refresh_token 쿠키 값이다. 쿠키가 없으면 null이다
    public TokenRefreshResult refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            log.debug("토큰 재발급 거부: reason=missing_cookie, sessionId=null");
            throw new BusinessException(CommonErrorCode.INVALID_TOKEN);
        }

        // 해당 토큰 값을 못 찾을 경우 사용되었던 토큰이었는지 검증하도록 넘겨짐
        RefreshTokenSession session = refreshTokenRepository.find(rawToken)
                .orElseThrow(() -> rejectMissingToken(rawToken));

        // 로그인 후의 정지·탈퇴를 반영한다. 세션을 남기면 이후 요청도 같은 판정을 반복하므로 지운다(M00-04)
        Optional<LoginUser> activeUser = activeUserLookupService.findActive(session.userId());
        if (activeUser.isEmpty()) {
            refreshTokenRepository.revokeSession(session.sessionId());
            throw rejected("user_inactive", session.sessionId());
        }
        LoginUser user = activeUser.get();

        // 판매자 승인·취소가 재발급 시점에 토큰에 반영되도록 매번 다시 계산한다
        Set<AuthRole> roles = authRoleCalculator.calculate(user.publicId(), user.role());
        IssuedAccessToken accessToken = jwtProvider.issue(user.publicId(), roles);
        String newRefreshToken = refreshTokenGenerator.generate();

        // sessionId·sessionExpiresAt을 그대로 이어받아 재발급을 반복해도 절대 만료(로그인 + 30일)를 넘지 않는다
        Duration refreshTokenMaxAge = rotate(rawToken, newRefreshToken, session)
                .orElseThrow(() -> rejected("race_lost", session.sessionId()));

        log.info("토큰 재발급 성공: userId={}, sessionId={}", user.publicId(), session.sessionId());
        return new TokenRefreshResult(accessToken, newRefreshToken, refreshTokenMaxAge);
    }

    // 절대 만료까지 1초 미만이면 저장소가 교체 전에 INVALID_TOKEN을 던진다(M00-05). 사유를 남기고 그대로 던진다
    private Optional<Duration> rotate(String rawToken, String newRefreshToken, RefreshTokenSession session) {
        try {
            return refreshTokenRepository.rotate(rawToken, newRefreshToken, session);
        } catch (BusinessException exception) {
            logRejection("session_expired", session.sessionId());
            throw exception;
        }
    }

    // 저장소에 없는 토큰은 교체된 적 있는지 확인한다. 유예가 지난 재사용이면 저장소가 세션을 폐기한다(M00-02·03)
    private BusinessException rejectMissingToken(String rawToken) {
        RefreshTokenReuseCheck check = refreshTokenRepository.checkReuse(rawToken);
        return switch (check.status()) {
            case NOT_FOUND -> rejected("not_found", null);
            case WITHIN_GRACE -> rejected("reuse_within_grace", check.sessionId());
            case REUSE_DETECTED -> {
                log.warn("토큰 재발급 거부: reason=reuse_detected, sessionId={}. 세션을 폐기했습니다", check.sessionId());
                yield new BusinessException(CommonErrorCode.INVALID_TOKEN);
            }
        };
    }

    private static BusinessException rejected(String reason, UUID sessionId) {
        logRejection(reason, sessionId);
        return new BusinessException(CommonErrorCode.INVALID_TOKEN);
    }

    private static void logRejection(String reason, UUID sessionId) {
        log.info("토큰 재발급 거부: reason={}, sessionId={}", reason, sessionId);
    }
}
