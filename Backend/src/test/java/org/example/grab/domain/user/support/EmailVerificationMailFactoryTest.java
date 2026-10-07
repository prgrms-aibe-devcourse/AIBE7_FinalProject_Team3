package org.example.grab.domain.user.support;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationMailFactoryTest {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "048213";

    private final EmailVerificationMailFactory factory = new EmailVerificationMailFactory();

    @Test
    @DisplayName("받는 주소는 넘긴 이메일이고, 제목에는 코드가 없다")
    void createsMessageWithoutCodeInSubject() {
        // when
        EmailMessage message = factory.create(EMAIL, CODE);

        // then
        assertThat(message.to()).isEqualTo(EMAIL);
        assertThat(message.subject()).isEqualTo("[GRAB] 이메일 인증 코드 안내").doesNotContain(CODE);
    }

    @Test
    @DisplayName("HTML·텍스트 본문에 앞자리 0을 포함한 코드와 유효 시간 5분이 들어간다")
    void bodiesContainCodeAndValidity() {
        // when
        EmailMessage message = factory.create(EMAIL, CODE);

        // then
        assertThat(message.htmlBody()).contains(CODE).contains("5분 동안 유효").startsWith("<!DOCTYPE html>");
        assertThat(message.textBody()).contains("인증 코드: " + CODE).contains("5분 동안 유효").doesNotContain("<");
    }

    @Test
    @DisplayName("메시지의 toString(로그에 남는 값)에 코드·받는 주소가 없다")
    void toStringHasNoCodeOrAddress() {
        // when
        String text = factory.create(EMAIL, CODE).toString();

        // then
        assertThat(text).doesNotContain(CODE).doesNotContain(EMAIL);
    }
}
