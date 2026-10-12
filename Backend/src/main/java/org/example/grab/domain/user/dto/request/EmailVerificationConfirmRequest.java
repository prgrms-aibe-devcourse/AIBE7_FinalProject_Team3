package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.example.grab.domain.user.support.EmailNormalizer;
import org.example.grab.domain.user.validation.ValidEmail;

/*
    이메일 인증 코드 확인(MEMBER_AUTH.md 1.2.2).
    code 형식 위반은 VALIDATION_FAILED이고 확인 시도 횟수에 포함하지 않는다. 검증은 서비스에 닿기 전에 끝나므로 횟수가 늘지 않는다.
 */
public record EmailVerificationConfirmRequest(
        // Redis에는 코드가 이메일 기준(HMAC(이메일) 키)으로 저장. 그래서 어느 이메일의 코드와 비교할지 알아야 함.
        @NotEmpty(message = "이메일은 필수입니다.") @ValidEmail String email,
        @NotNull(message = "인증 코드는 필수입니다.")
        @Pattern(regexp = "[0-9]{6}", message = "인증 코드는 숫자 6자리여야 합니다.") String code
) {

    // code는 가공하지 않는다. 앞뒤 공백이 있으면 형식 위반이다
    public EmailVerificationConfirmRequest(String email, String code) {
        this.email = EmailNormalizer.normalize(email);
        this.code = code;
    }

    // 인증 코드와 이메일은 로그·예외 메시지에 남기지 않는다(NFR-011)
    @Override
    public String toString() {
        return "EmailVerificationConfirmRequest[email=masked, code=masked]";
    }
}
