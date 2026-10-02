package org.example.grab.domain.mail.service;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailSenderTest {

    private static final String FROM = "no-reply@grab.local";

    @Test
    @DisplayName("발신 주소·받는 사람·제목과 HTML·텍스트 본문을 담아 발송한다")
    // SMTP 서버 없이 JavaMailSender에 넘기는 메일 구성만 확인한다. 실제 수신 검증은 GreenMail 테스트(M05)에서 한다
    void buildsMimeMessageWithAllParts() throws Exception {
        // given
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM);
        EmailMessage message = new EmailMessage(
                "user@example.com", "[GRAB] 이메일 인증 코드", "<p>인증 코드: 482913</p>", "인증 코드: 482913");

        // when
        sender.send(message);
        MimeMessage mimeMessage = prepared(mailSender);

        // then
        assertThat(mimeMessage.getFrom()).extracting(Address::toString).containsExactly(FROM);
        assertThat(mimeMessage.getRecipients(Message.RecipientType.TO))
                .extracting(Address::toString).containsExactly("user@example.com");
        assertThat(mimeMessage.getSubject()).isEqualTo("[GRAB] 이메일 인증 코드");

        List<Part> textParts = textParts(mimeMessage);
        assertThat(textParts).extracting(Part::getContent)
                .containsExactlyInAnyOrder("인증 코드: 482913", "<p>인증 코드: 482913</p>");
        assertThat(textParts).anySatisfy(part -> assertThat(part.isMimeType("text/plain")).isTrue());
        assertThat(textParts).anySatisfy(part -> assertThat(part.isMimeType("text/html")).isTrue());
    }

    @Test
    @DisplayName("한글 제목과 본문을 UTF-8로 인코딩한다")
    // 인코딩을 지정하지 않으면 플랫폼 기본 문자셋으로 인코딩되어 받는 쪽에서 한글이 깨질 수 있다
    void encodesKoreanWithUtf8() throws Exception {
        // given
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM);

        // when
        sender.send(new EmailMessage("user@example.com", "인증 코드", "<p>안녕하세요</p>", "안녕하세요"));
        MimeMessage mimeMessage = prepared(mailSender);

        // then
        assertThat(mimeMessage.getHeader("Subject", null)).startsWithIgnoringCase("=?UTF-8?");
        assertThat(textParts(mimeMessage))
                .allSatisfy(part -> assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8"));
    }

    private static MimeMessage prepared(JavaMailSender mailSender) throws Exception {
        ArgumentCaptor<MimeMessagePreparator> captor = ArgumentCaptor.forClass(MimeMessagePreparator.class);
        verify(mailSender).send(captor.capture());
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        captor.getValue().prepare(mimeMessage);
        mimeMessage.saveChanges();
        return mimeMessage;
    }

    private static List<Part> textParts(Part part) throws Exception {
        List<Part> parts = new ArrayList<>();
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                parts.addAll(textParts(bodyPart));
            }
        } else if (part.isMimeType("text/*")) {
            parts.add(part);
        }
        return parts;
    }
}
