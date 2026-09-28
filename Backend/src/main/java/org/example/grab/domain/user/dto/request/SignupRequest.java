package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidPassword;

// 역할·상태·가입 경로는 서버가 정하므로 입력으로 받지 않는다(MEMBER_AUTH.md 1.2.3)
public record SignupRequest(
        // @NotBlank는 공백만 있는 값을 필수값 위반으로 분류하므로, 비밀번호 규칙 위반(INVALID_PASSWORD)이 되도록 @NotEmpty를 쓴다
        @NotEmpty @ValidPassword String password,
        String nickname
) {

    /*
        JSON 바인딩도 이 생성자를 거치므로, Bean Validation·중복 검사·저장이 모두 앞뒤 공백을 제거한 닉네임을 쓴다.
        trim()은 한글 IME에서 섞이는 전각 스페이스(U+3000)를 남기므로 strip()을 쓴다.
        null은 필수값 검증에서 VALIDATION_FAILED로 처리하도록 그대로 둔다.
        비밀번호는 trim·가공하지 않는다(MEMBER_AUTH.md 1.2.3).
     */
    public SignupRequest(String password, String nickname) {
        this.password = password;
        this.nickname = nickname == null ? null : nickname.strip();
    }
}
