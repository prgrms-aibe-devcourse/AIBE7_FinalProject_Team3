package org.example.grab.domain.auth.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.support.EmailNormalizer;

/*
    LOCAL 로그인 요청(MEMBER_AUTH.md 1.3).
    필수값만 검사한다(GR-34 M00-04). 이메일 형식·비밀번호 규칙은 가입 때의 규칙이라 로그인에서는 보지 않는다.
    규칙이 강화돼도 기존 회원이 로그인할 수 있어야 하고, 규칙에 맞지 않는 값은 어차피 INVALID_CREDENTIALS가 된다.
    @NotEmpty의 기본 문구는 실행 환경의 로케일에 따라 영어·한국어로 바뀌므로 문구를 직접 지정한다.
 */
public record LoginRequest(
        // 생성자에서 정규화하므로 공백만 있던 값은 ""가 되어 @NotEmpty로 걸린다(VALIDATION_FAILED)
        @NotEmpty(message = "이메일은 필수입니다.") String email,
        // 비밀번호는 가공하지 않는다. 공백만 있는 값도 그대로 검증해 INVALID_CREDENTIALS가 된다
        @NotEmpty(message = "비밀번호는 필수입니다.") String password
) {

    // JSON 바인딩도 이 생성자를 거치므로, 검증·회원 조회가 모두 가입 때와 같은 규칙으로 정규화한 이메일을 쓴다
    public LoginRequest(String email, String password) {
        this.email = EmailNormalizer.normalize(email);
        this.password = password;
    }

    /*
        record가 자동으로 만드는 toString()은 비밀번호 원문을 출력해 로그·예외 메시지·디버거에 남는다(NFR-001).
        이메일도 개인정보라 함께 가린다.
     */
    @Override
    public String toString() {
        return "LoginRequest[email=masked, password=masked]";
    }
}
