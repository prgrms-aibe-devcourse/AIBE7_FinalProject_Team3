package org.example.grab.domain.auth.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/*
    Refresh Token 키에 저장하는 값(GR-33 M00-04). 원문과 해시는 담지 않는다.
    - userId: users.id
    - sessionId: 로그인 한 번에 하나. 재발급은 이어받는다
    - sessionExpiresAt: 절대 만료 시각. 재발급은 이어받는다
    토큰이 최대 30일 남아 배포 후에도 옛 형식이 남을 수 있으므로 필드는 추가만 하고, 모르는 필드는 전역 설정과 관계없이 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true) // Jackson이 JSON을 자바 객체로 변환할 때, 객체 없는 필드를 무시하도록 하는 설정
public record RefreshTokenSession(
        Long userId,
        UUID sessionId,
        Instant sessionExpiresAt
) {

    public RefreshTokenSession(Long userId, UUID sessionId, Instant sessionExpiresAt) {
        // Redis에서 읽은 값에 필드가 빠졌을 때도 역직렬화 실패로 처리되도록 생성자에서 막는다
        if (userId == null || sessionId == null || sessionExpiresAt == null) {
            throw new IllegalArgumentException("Refresh Token 세션의 userId, sessionId, sessionExpiresAt은 필수입니다.");
        }
        this.userId = userId;
        this.sessionId = sessionId;
        this.sessionExpiresAt = sessionExpiresAt;
    }
}
