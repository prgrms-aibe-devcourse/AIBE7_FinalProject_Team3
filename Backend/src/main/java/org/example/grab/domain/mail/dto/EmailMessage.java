package org.example.grab.domain.mail.dto;

import java.util.List;
import java.util.Objects;

/**
 * 발송할 메일 한 통. 발신 주소는 구현체가 grab.mail.from 설정으로 채운다.
 *
 * @param to       받는 사람 주소. BCC 전용 메일에서는 null이고, 구현체가 받는 사람 칸을 수신자 없는 표시용 값으로 채운다.
 * @param bcc      숨은 참조 수신자. 서로의 주소를 보지 못한다. 받는 사람이 한 명인 메일에서는 비어 있다.
 * @param subject  메일 제목
 * @param htmlBody HTML 본문
 * @param textBody HTML을 표시하지 못하는 메일 클라이언트를 위한 텍스트 본문
 */
public record EmailMessage(
        String to,
        List<String> bcc,
        String subject,
        String htmlBody,
        String textBody
) {

    /** 받는 사람이 한 명인 메일(인증 코드 등). */
    public EmailMessage(String to, String subject, String htmlBody, String textBody) {
        this(to, List.of(), subject, htmlBody, textBody);
    }

    /*
        같은 본문을 여러 명에게 보낼 때 쓴다(GR-69). 수신자를 BCC에 담아 서로의 주소가 보이지 않게 하고,
        SMTP 트랜잭션을 한 번으로 줄인다. 통마다 메일을 만들면 수신자당 MAIL FROM·RCPT·DATA·본문을 모두 반복하지만,
        BCC로 묶으면 수신자당 RCPT 한 번으로 끝난다(Brevo 실측: 통당 0.643초 → 수신자당 0.134초).
        받는 사람 칸은 구현체가 채운다. SMTP는 To와 BCC를 모두 수신자로 쓰므로 거기에 실제 주소를 넣으면
        그 주소도 수신자가 돼 사본이 한 통 더 가고 발송처의 일일 한도를 메일마다 1통씩 더 쓴다.
     */
    public static EmailMessage toBcc(List<String> bcc, String subject, String htmlBody, String textBody) {
        return new EmailMessage(null, bcc, subject, htmlBody, textBody);
    }

    public EmailMessage {
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        // 받는 사람이 아무도 없는 메일은 만들 수 없다. 보내 봐야 SMTP가 거부한다
        if (to == null && bcc.isEmpty()) {
            throw new IllegalArgumentException("받는 사람이 없는 메일은 만들 수 없다");
        }
        Objects.requireNonNull(subject);
        Objects.requireNonNull(htmlBody);
        Objects.requireNonNull(textBody);
    }

    /** 이 메일이 실제로 닿는 사람 수. 로그에 주소 대신 남긴다. */
    public int recipientCount() {
        return bcc.isEmpty() ? 1 : bcc.size();
    }

    // 본문에는 인증 코드가, 받는 주소에는 개인정보가 들어가므로 로그에 원문을 남기지 않는다(NFR-011).
    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
