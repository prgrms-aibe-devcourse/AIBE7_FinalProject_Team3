package org.example.grab.domain.user.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/*
    이메일 인증 코드(숫자 6자리)를 만든다(GR-61 M03-01).
    000000~999999를 고르게 뽑아야 대입 성공 확률이 시도마다 100만분의 1로 유지되므로 SecureRandom을 쓴다.
 */
@Component
public class EmailVerificationCodeGenerator {

    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();

    // 앞자리 0도 유지하도록 6자리로 채운다(예: 7 → "000007")
    public String generate() {
        return String.format("%06d", secureRandom.nextInt(CODE_BOUND));
    }
}
