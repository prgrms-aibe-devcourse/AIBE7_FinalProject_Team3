package org.example.grab.domain.mail.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("받는 주소·제목·본문 중 하나라도 null이면 생성하지 않는다")
    void rejectsNullFields() {
        // when & then
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage(null, "제목", "<p>본문</p>", "본문"));
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", null, "<p>본문</p>", "본문"));
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", "제목", null, "본문"));
        assertThatNullPointerException().isThrownBy(() -> new EmailMessage("user@example.com", "제목", "<p>본문</p>", null));
    }
}
