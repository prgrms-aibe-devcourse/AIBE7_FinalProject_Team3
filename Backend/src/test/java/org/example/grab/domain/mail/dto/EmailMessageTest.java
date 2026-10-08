package org.example.grab.domain.mail.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EmailMessageTest {

    @Test
    @DisplayName("toString에 받는 주소와 본문을 남기지 않는다")
    // 메시지 객체가 로그에 그대로 찍혀도 인증 코드와 받는 주소가 노출되지 않는지 확인하는 테스트
    void excludesRecipientAndBodiesFromToString() {
        // given
        EmailMessage message = new EmailMessage(
                "user@example.com", "[GRAB] 이메일 인증 코드", "<p>인증 코드 482913</p>", "인증 코드 482913");

        // when
        String text = message.toString();

        // then
        assertThat(text).contains("[GRAB] 이메일 인증 코드");
        assertThat(text).doesNotContain("user@example.com", "482913");
    }

    @Test
    @DisplayName("제목·본문 중 하나라도 null이면 생성하지 않는다")
    void rejectsNullFields() {
        // when & then
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", null, "<p>본문</p>", "본문"));
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", "제목", null, "본문"));
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", "제목", "<p>본문</p>", null));
    }

    @Test
    @DisplayName("받는 사람이 한 명도 없으면 생성하지 않는다")
    // BCC 도입으로 to가 null일 수 있게 됐지만, to와 bcc가 모두 비면 보내 봐야 SMTP가 거부하므로 확인하는 테스트
    void rejectsMessageWithoutAnyRecipient() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailMessage(null, "제목", "<p>본문</p>", "본문"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EmailMessage.toBcc(List.of(), "제목", "<p>본문</p>", "본문"));
    }

    @Test
    @DisplayName("BCC 메일은 받는 사람 칸이 비고 수신자가 bcc에 담긴다")
    // 수신자끼리 서로의 주소를 보지 못해야 하므로 to가 아니라 bcc에 들어가는지 확인하는 테스트
    void keepsRecipientsInBcc() {
        // given
        List<String> recipients = List.of("a@example.com", "b@example.com");

        // when
        EmailMessage message = EmailMessage.toBcc(recipients, "[GRAB] 판매 시작", "<p>본문</p>", "본문");

        // then
        assertThat(message.to()).isNull();
        assertThat(message.bcc()).containsExactlyElementsOf(recipients);
        assertThat(message.recipientCount()).isEqualTo(2);
        // 주소는 로그에 남지 않아야 한다(NFR-011)
        assertThat(message.toString()).doesNotContain("a@example.com", "b@example.com");
    }

    @Test
    @DisplayName("받는 사람이 한 명인 메일은 bcc가 비고 수신자 수가 1이다")
    void countsSingleRecipient() {
        // given & when
        EmailMessage message = new EmailMessage("user@example.com", "제목", "<p>본문</p>", "본문");

        // then
        assertThat(message.bcc()).isEmpty();
        assertThat(message.recipientCount()).isEqualTo(1);
    }
}
