package org.example.grab.domain.user.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {

    INVALID_EMAIL(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 규칙을 충족하지 않습니다."),
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임이 규칙을 충족하지 않습니다."),

    // LOCAL 회원가입 이메일 인증(MEMBER_AUTH.md 1.2). 이메일 인증은 회원가입 단계라 user 도메인에 둔다(GR-61 M01-01)
    EMAIL_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "이메일 인증 코드가 일치하지 않습니다."),
    EMAIL_VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "유효한 이메일 인증 코드가 없습니다."),
    // 만료된 가입 컨텍스트도 이 코드로 응답한다. 만료와 미존재를 구분하지 않는다(GR-61 M00-03)
    EMAIL_SIGNUP_CONTEXT_INVALID(HttpStatus.UNAUTHORIZED, "이메일 인증 정보가 유효하지 않습니다."),
    // LOCAL 로그인(MEMBER_AUTH.md 1.3). 회원 없음·비밀번호 불일치·소셜 회원을 구분하지 않아 계정 존재 여부를 드러내지 않는다(GR-34 M00-03)
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    // 비밀번호가 맞은 SUSPENDED·WITHDRAWN 회원에게만 응답한다. 본인 확인은 됐지만 이용할 수 없는 상태라 403이다(GR-34 M00-02)
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "이용할 수 없는 계정입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    // 대소문자를 무시한 닉네임 중복(MEMBER_AUTH.md 1.2.3). 사전 검사와 uq_users_nickname_lower 위반 모두 이 코드로 응답한다(GR-29)
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "이메일 인증 코드 확인 시도 횟수를 초과했습니다."),
    EMAIL_VERIFICATION_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "이메일 인증 코드를 다시 요청할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    UserErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
