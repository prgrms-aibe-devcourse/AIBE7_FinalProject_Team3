package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.validation.ValidNickname;
import org.example.grab.domain.user.validation.ValidPassword;
import org.example.grab.global.validation.SensitiveValue;

/*
    역할·상태·가입 경로는 서버가 정하므로 입력으로 받지 않는다(MEMBER_AUTH.md 1.2.3).
    제약 문구는 응답의 fieldErrors[].reason으로 나가므로 입력값을 넣지 않고 위반 이유만 적는다.
    @NotEmpty의 기본 문구는 실행 환경의 로케일에 따라 영어·한국어로 바뀌므로 문구를 직접 지정한다.
 */
public record SignupRequest(
        // @NotBlank는 공백만 있는 값을 필수값 위반으로 분류하므로, 비밀번호 규칙 위반(INVALID_PASSWORD)이 되도록 @NotEmpty를 쓴다
        // 검증 실패 시 FieldError에 비밀번호 원문이 남지 않도록 거부된 값을 가린다
        @SensitiveValue @NotEmpty(message = "비밀번호는 필수입니다.") @ValidPassword String password,
        // 생성자에서 앞뒤 공백을 제거하므로 공백만 있던 값은 ""가 되어 @NotEmpty로 걸린다
        // @ValidNickname이 null·""를 건너뛰는 조건과 정확히 맞물려, 한 값에 두 제약이 함께 위반을 보고하지 않는다
        @NotEmpty(message = "닉네임은 필수입니다.") @ValidNickname String nickname
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

    /*
        record가 자동으로 만드는 toString()은 모든 필드를 그대로 출력해, 로그·예외 메시지·디버거에 비밀번호 원문이 남는다.
        비밀번호는 null·길이 여부도 드러나지 않도록 항상 같은 값으로 가리고, 공개 정보인 닉네임만 남긴다.
     */
    @Override
    public String toString() {
        return "SignupRequest[password=masked, nickname=" + nickname + "]";
    }
}
