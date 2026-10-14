package org.example.grab.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.auth.repository.RefreshTokenSession;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/*
    로그아웃(MEMBER_AUTH.md 1.8, GR-36). 쿠키의 Refresh Token이 속한 세션 하나를 폐기한다.
    결과는 멱등하다. 쿠키가 없거나 지울 세션이 없어도 예외 없이 끝나고, 호출하는 쪽은 같은 204를 응답한다(M00-01).
    현재 토큰이면 find로 세션을 얻어 폐기하고, 이미 교체된 토큰이면 사용됨 표식으로 세션을 찾아 폐기한다(M00-02).
    표식까지 보는 이유는 탈취된 토큰으로 먼저 재발급된 경우에도 사용자의 로그아웃으로 세션을 끊기 위해서다.
    Redis 오류는 잡지 않아 500이 된다. 폐기를 확인하지 못했는데 성공으로 응답하면 쿠키만 지워지고 토큰이 남는다(M00-03).
    토큰 원문·해시는 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;

    // rawToken은 refresh_token 쿠키 값이다. 쿠키가 없으면 null이다
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            log.info("로그아웃: reason=missing_cookie");
            return;
        }

        // 토큰이 Redis 저장소에 있으면 해당 세션, 토큰 폐기
        Optional<RefreshTokenSession> session = refreshTokenRepository.find(rawToken);
        if (session.isPresent()) {
            refreshTokenRepository.revokeSession(session.get().sessionId());
            log.info("로그아웃: userId={}, sessionId={}", session.get().userId(), session.get().sessionId());
            return;
        }

        // 만료·이미 로그아웃·발급한 적 없는 값이면 표식도 없어 폐기할 세션이 없다
        Optional<UUID> revokedSessionId = refreshTokenRepository.revokeSessionByUsedToken(rawToken);
        if (revokedSessionId.isPresent()) {
            log.info("로그아웃: reason=rotated_token, sessionId={}", revokedSessionId.get());
            return;
        }
        log.info("로그아웃: reason=not_found");
    }
}
