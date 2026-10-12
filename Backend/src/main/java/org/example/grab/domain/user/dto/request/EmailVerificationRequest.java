package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import org.example.grab.domain.user.support.EmailNormalizer;
import org.example.grab.domain.user.validation.ValidEmail;

/*
    이메일 인증 코드 요청(MEMBER_AUTH.md 1.2.1).
    @NotEmpty의 기본 문구는 실행 환경의 로케일에 따라 영어·한국어로 바뀌므로 문구를 직접 지정한다.
 */
public record EmailVerificationRequest(
        // 생성자에서 정규화하므로 공백만 있던 값은 ""가 되어 @NotEmpty로 걸린다(VALIDATION_FAILED)
        @NotEmpty(message = "이메일은 필수입니다.") @ValidEmail String email
) {

    // JSON 바인딩도 이 생성자를 거치므로, Bean Validation·Redis 키·발송이 모두 정규화한 이메일을 쓴다
    public EmailVerificationRequest(String email) {
        this.email = EmailNormalizer.normalize(email);
    }

    // 이메일은 개인정보라 로그·예외 메시지에 남기지 않는다(NFR-011)
    @Override
    public String toString() {
        return "EmailVerificationRequest[email=masked]";
    }
}
