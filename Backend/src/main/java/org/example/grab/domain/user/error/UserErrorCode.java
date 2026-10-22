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
    // 내 정보 수정·비밀번호 변경(MEMBER_AUTH.md 1.10·1.11, GR-129). 401이면 프론트엔드가 재발급·로그아웃 흐름을 타므로 모두 400이다(M00-01)
    // 앞뒤 공백을 제거한 값이 현재 닉네임과 대소문자까지 같을 때다. 대소문자만 바꾸는 것은 허용한다(M00-05)
    NICKNAME_UNCHANGED(HttpStatus.BAD_REQUEST, "현재 닉네임과 같습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    // 비밀번호가 없는 KAKAO·GOOGLE 회원의 비밀번호 변경 요청이다
    PASSWORD_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "비밀번호를 사용하지 않는 계정입니다."),
    // 현재 비밀번호 확인이 성공한 뒤에만 판단한다. 현재 비밀번호를 모르는 요청에는 PASSWORD_MISMATCH가 먼저 나간다(M00-04)
    PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "새 비밀번호가 현재 비밀번호와 같습니다."),
    // 소셜 로그인(MEMBER_AUTH.md 1.4·1.5, GR-46). OAUTH2_AUTHENTICATION_FAILED·OAUTH2_EMAIL_REQUIRED는 JSON이 아니라
    // 프론트엔드 콜백 주소의 error 쿼리로만 전달되므로 HTTP 상태는 쓰이지 않는다
    UNSUPPORTED_OAUTH2_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다."),
    OAUTH2_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),
    OAUTH2_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "소셜 계정의 검증된 이메일이 필요합니다."),
    // 같은 이메일의 다른 가입 방식 계정을 자동으로 연결하지 않는다(AUTH-008). 콜백에서는 error 쿼리로만 전달된다
    ACCOUNT_LINK_REQUIRED(HttpStatus.CONFLICT, "같은 이메일로 가입된 다른 계정이 있습니다."),
    // 만료·없음·재사용·조작을 구분하지 않는다(GR-46 M00-06, EMAIL_SIGNUP_CONTEXT_INVALID와 같은 이유)
    OAUTH2_SIGNUP_CONTEXT_INVALID(HttpStatus.UNAUTHORIZED, "소셜 가입 컨텍스트가 유효하지 않습니다."),
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
