package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidNickname;

/*
    내 닉네임 수정 요청(MEMBER_AUTH.md 1.10, GR-129). 닉네임만 받는다.
    이메일은 조회만 가능하고 사용자 ID는 토큰의 본인으로 정하므로 입력으로 받지 않는다.
    닉네임 규칙·앞뒤 공백 제거는 LOCAL·소셜 가입(SignupRequest·OAuth2SignupRequest)과 같다.
 */
public record UserNicknameUpdateRequest(
        @NotEmpty(message = "닉네임은 필수입니다.") @ValidNickname String nickname
) {

    /*
        JSON 바인딩도 이 생성자를 거치므로, 검증·현재 닉네임 비교(NICKNAME_UNCHANGED)·중복 검사·저장이 모두 공백을 제거한 값을 쓴다.
        trim()은 전각 스페이스(U+3000)를 남기므로 strip()을 쓴다. null은 필수값 검증에서 VALIDATION_FAILED로 처리하도록 그대로 둔다.
     */
    public UserNicknameUpdateRequest(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }
}
