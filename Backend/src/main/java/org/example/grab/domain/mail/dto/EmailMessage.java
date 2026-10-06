package org.example.grab.domain.mail.dto;

import java.util.Objects;

/**
 * 발송할 메일 한 통. 발신 주소는 구현체가 grab.mail.from 설정으로 채운다.
 *
 * @param to       받는 사람 주소
 * @param subject  메일 제목
 * @param htmlBody HTML 본문
 * @param textBody HTML을 표시하지 못하는 메일 클라이언트를 위한 텍스트 본문
 */
public record EmailMessage(
        String to,
        String subject,
        String htmlBody,
        String textBody
) {

    public EmailMessage(String to, String subject, String htmlBody, String textBody) {
        this.to = Objects.requireNonNull(to);
        this.subject = Objects.requireNonNull(subject);
        this.htmlBody = Objects.requireNonNull(htmlBody);
        this.textBody = Objects.requireNonNull(textBody);
    }

    // 본문에는 인증 코드가, 받는 주소에는 개인정보가 들어가므로 로그에 원문을 남기지 않는다(NFR-011).
    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
