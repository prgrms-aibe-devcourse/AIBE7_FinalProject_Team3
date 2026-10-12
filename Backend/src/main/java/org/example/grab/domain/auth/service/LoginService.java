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
import org.example.grab.domain.user.service.UserCredentialService;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.IssuedAccessToken;
import org.example.grab.global.security.jwt.JwtProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/*
    LOCAL 로그인(MEMBER_AUTH.md 1.3). 자격 확인 → roles 계산 → Access Token 발급 → Refresh 세션 저장 순서로 처리한다.
    - 로그인마다 새 세션(sessionId, 절대 만료 = 로그인 시각 + 30일)을 만든다(GR-33 인계)
    - Redis 저장이 성공한 뒤에만 결과를 돌려준다. 저장 실패 시 서버가 모르는 토큰을 쿠키로 주지 않기 위해서다.
      Redis 오류는 잡지 않아 500 INTERNAL_SERVER_ERROR가 되고 쿠키는 나가지 않는다
    - 실패(INVALID_CREDENTIALS·ACCOUNT_DISABLED)는 GlobalExceptionHandler가 오류 코드만 로그로 남긴다
    서비스 트랜잭션을 두지 않는다. 회원·판매자 조회는 각자 끝나고, Redis 저장은 DB 트랜잭션과 묶이지 않는다.
    시각 계산(쿠키 Max-Age·세션 만료)은 JwtProvider·RefreshTokenTtlCalculator가 하므로 이 클래스는 시계를 갖지 않는다.
    이메일·비밀번호·토큰은 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserCredentialService userCredentialService;
    private final AuthRoleCalculator authRoleCalculator;
    private final JwtProvider jwtProvider;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenTtlCalculator refreshTokenTtlCalculator;
    private final RefreshTokenRepository refreshTokenRepository;

    // normalizedEmail은 요청 DTO에서 정규화한 값, rawPassword는 가공하지 않은 원문이다
    public LoginResult login(String normalizedEmail, String rawPassword) {
        LoginUser user = userCredentialService.authenticate(normalizedEmail, rawPassword);
        Set<AuthRole> roles = authRoleCalculator.calculate(user.publicId(), user.role());
        IssuedAccessToken accessToken = jwtProvider.issue(user.publicId(), roles);

        String refreshToken = refreshTokenGenerator.generate();
        UUID sessionId = UUID.randomUUID();
        Instant sessionExpiresAt = refreshTokenTtlCalculator.newSessionExpiresAt();
        Duration refreshTokenMaxAge = refreshTokenRepository.save(
                refreshToken, new RefreshTokenSession(user.id(), sessionId, sessionExpiresAt));

        log.info("로그인 성공: userId={}, sessionId={}", user.publicId(), sessionId);
        return new LoginResult(user, roles, accessToken, refreshToken, refreshTokenMaxAge);
    }
}
