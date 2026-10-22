package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidPassword;

/*
    내 비밀번호 변경 요청(MEMBER_AUTH.md 1.11, GR-129). LOCAL 회원만 쓸 수 있다.
    두 비밀번호 모두 trim·가공하지 않는다(MEMBER_AUTH.md 1.2.3).
    @NotEmpty의 기본 문구는 실행 환경의 로케일에 따라 영어·한국어로 바뀌므로 문구를 직접 지정한다.
 */
public record PasswordChangeRequest(
        // 필수값만 검사하고 비밀번호 규칙은 보지 않는다(M00-04). 로그인(LoginRequest)과 같이 규칙이 강화돼도
        // 기존 비밀번호로 변경할 수 있어야 하고, 맞지 않는 값은 저장된 해시와 달라 PASSWORD_MISMATCH가 된다
        @NotEmpty(message = "현재 비밀번호는 필수입니다.") String currentPassword,
        @NotEmpty(message = "새 비밀번호는 필수입니다.") @ValidPassword String newPassword
) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=masked, newPassword=masked]";
    }
}
