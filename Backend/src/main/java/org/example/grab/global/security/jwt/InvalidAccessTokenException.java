package org.example.grab.global.security.jwt;

import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/*
    Access Token 검증 실패. 사유와 관계없이 응답은 401 INVALID_TOKEN 하나로 나간다(M00-09).
    AuthenticationException을 상속해 필터가 그대로 AuthenticationEntryPoint에 넘길 수 있게 한다.

    jjwt 예외 메시지에는 Claim 값이나 토큰 일부가 들어갈 수 있으므로 메시지를 옮기지 않고 cause로도 연결하지 않는다.
    만료·변조 등 원인 구분은 운영 로그용 reason으로만 남긴다.
 */
@Getter
public class InvalidAccessTokenException extends AuthenticationException {

    private final Reason reason;

    public InvalidAccessTokenException(Reason reason) {
        super("유효하지 않은 Access Token입니다. reason=" + reason);
        this.reason = reason;
    }

    public enum Reason {
        // 토큰 값이 없거나 공백
        EMPTY,
        // JWT 형식이 아님
        MALFORMED,
        // 서명이 없는 토큰 등 지원하지 않는 형태
        UNSUPPORTED,
        // 서명 불일치, 다른 키, 허용 외 알고리즘
        SIGNATURE,
        // exp + leeway가 지남
        EXPIRED,
        // iss·aud 불일치, 필수 Claim 누락
        CLAIM,
        // 위 분류에 들지 않는 jjwt 검증 실패
        OTHER
    }
}
