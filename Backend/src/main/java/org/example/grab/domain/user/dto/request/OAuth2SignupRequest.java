package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidNickname;

/*
    소셜 회원가입 완료 요청(MEMBER_AUTH.md 1.6). 닉네임만 받는다.
    제공자·이메일은 가입 컨텍스트 값만 쓰므로 입력으로 받지 않는다. 휴대폰 번호도 받지 않는다.
    닉네임 규칙·앞뒤 공백 제거는 LOCAL 가입(SignupRequest)과 같다.
 */
public record OAuth2SignupRequest(
        // 생성자에서 앞뒤 공백을 제거하므로 공백만 있던 값은 ""가 되어 @NotEmpty로 걸린다(SignupRequest와 같음)
        @NotEmpty(message = "닉네임은 필수입니다.") @ValidNickname String nickname
) {

    // trim()은 전각 스페이스(U+3000)를 남기므로 strip()을 쓴다. null은 필수값 검증에서 VALIDATION_FAILED로 처리하도록 그대로 둔다
    public OAuth2SignupRequest(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }
}
