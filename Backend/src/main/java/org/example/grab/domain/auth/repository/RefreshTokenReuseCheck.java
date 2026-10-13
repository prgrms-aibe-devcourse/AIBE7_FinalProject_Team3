package org.example.grab.domain.auth.repository;

import java.util.UUID;

/*
    저장소에 없는 Refresh Token의 재사용 확인 결과(GR-35 M00-02·03).
    응답은 모두 INVALID_TOKEN이고, 이 결과는 로그 사유와 세션 폐기 여부를 가르는 데만 쓴다.
    sessionId는 사용됨 표식에서 읽은 값이며 NOT_FOUND이면 null이다.
 */
public record RefreshTokenReuseCheck(
        Status status,
        UUID sessionId
) {

    public enum Status {
        // 발급한 적 없거나 표식까지 만료된 토큰
        NOT_FOUND,
        // 교체 직후 유예 안의 재사용. 여러 탭의 동시 재발급으로 보고 세션을 유지한다
        WITHIN_GRACE,
        // 유예가 지난 재사용. 탈취로 보고 세션의 현재 토큰을 폐기했다
        REUSE_DETECTED
    }
}
