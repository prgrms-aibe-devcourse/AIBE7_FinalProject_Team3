package org.example.grab.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.example.grab.domain.auth.support.AuthRoleCalculator;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/*
    본인 확인을 마친 회원에게 새 로그인 세션을 발급한다(GR-46 M00-08). LOCAL 로그인·소셜 로그인·소셜 가입 완료가 함께 쓴다.
    roles 계산 → Access Token 발급 → Refresh Token 생성 → 새 세션 저장 순서다(GR-34·35 인계).
    - 로그인마다 새 세션(sessionId, 절대 만료 = 로그인 시각 + 30일)을 만든다(GR-33 인계)
    - Refresh Token은 RefreshTokenRepository.save로만 저장한다. 토큰 키와 세션 키를 함께 써야 재사용 탐지가 동작한다(GR-35 인계)
    - Redis 저장이 성공한 뒤에만 결과를 돌려준다. 저장 실패 시 서버가 모르는 토큰을 쿠키로 주지 않기 위해서다.
      Redis 오류는 잡지 않고 전파한다
    시각 계산(쿠키 Max-Age·세션 만료)은 JwtProvider·RefreshTokenTtlCalculator가 하므로 이 클래스는 시계를 갖지 않는다.
    토큰은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionIssuer {

    private final AuthRoleCalculator authRoleCalculator;
    private final JwtProvider jwtProvider;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenTtlCalculator refreshTokenTtlCalculator;
    private final RefreshTokenRepository refreshTokenRepository;

    // user는 자격 확인(LOCAL) 또는 제공자 인증·상태 확인(소셜)을 마친 회원이다
    public LoginResult issue(LoginUser user) {
        Set<AuthRole> roles = authRoleCalculator.calculate(user.publicId(), user.role());
        IssuedAccessToken accessToken = jwtProvider.issue(user.publicId(), roles);

        String refreshToken = refreshTokenGenerator.generate();
        UUID sessionId = UUID.randomUUID();
        Instant sessionExpiresAt = refreshTokenTtlCalculator.newSessionExpiresAt();
        Duration refreshTokenMaxAge = refreshTokenRepository.save(
                refreshToken, new RefreshTokenSession(user.id(), sessionId, sessionExpiresAt));

        log.info("로그인 세션 발급: userId={}, sessionId={}", user.publicId(), sessionId);
        return new LoginResult(user, roles, accessToken, refreshToken, refreshTokenMaxAge);
    }
}
