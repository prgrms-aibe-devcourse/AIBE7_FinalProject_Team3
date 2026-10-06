package org.example.grab.domain.mail.service;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
    테스트 안에서 실행하는 GreenMail SMTP 서버로 실제 SMTP 발송을 확인한다. 실제 Gmail로는 보내지 않는다.
    JavaMailSender는 Boot 자동 구성(spring.mail.*)으로 만들어, 운영과 같은 방식으로 생성된 발송 빈을 검증한다.
 */
class SmtpEmailSenderGreenMailTest {

    private static final String FROM = "no-reply@grab.local";
    private static final String TO = "user@example.com";
    private static final String SUBJECT = "[GRAB] 이메일 인증 코드";
    private static final String HTML_BODY = "<p>안녕하세요. 인증 코드는 <b>482913</b>입니다.</p>";
    private static final String TEXT_BODY = "안녕하세요. 인증 코드는 482913입니다.";

    // 테스트마다 새 서버를 띄워 받은 메일이 섞이지 않게 하고, 빈 포트를 써서 다른 프로세스와 충돌하지 않게 한다
    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    @DisplayName("SMTP 구현체로 보낸 메일이 SMTP 서버에 도착한다")
    void deliversMailToSmtpServer() {
        // given & when
        contextRunner().run(context -> context.getBean(EmailSender.class).send(message()));

        // then
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessagesForDomain("example.com")).hasSize(1);
    }

    @Test
    @DisplayName("받는 사람·발신 주소·한글 제목이 보낸 값 그대로 도착한다")
    void deliversHeadersAsSent() throws Exception {
        // given & when
        contextRunner().run(context -> context.getBean(EmailSender.class).send(message()));
        MimeMessage received = onlyReceived();

        // then
        assertThat(received.getFrom()).extracting(Address::toString).containsExactly(FROM);
        assertThat(received.getRecipients(Message.RecipientType.TO)).extracting(Address::toString).containsExactly(TO);
        assertThat(received.getSubject()).isEqualTo(SUBJECT);
        // SMTP로 전달된 원본 헤더가 UTF-8로 인코딩되어 있어야 받는 쪽 문자셋과 관계없이 한글이 유지된다
        assertThat(received.getHeader("Subject", null)).startsWithIgnoringCase("=?UTF-8?");
    }

    @Test
    @DisplayName("HTML·텍스트 본문이 한글을 포함해 보낸 값 그대로 도착한다")
    void deliversBodiesAsSent() throws Exception {
        // given & when
        contextRunner().run(context -> context.getBean(EmailSender.class).send(message()));
        List<Part> textParts = textParts(onlyReceived());

        // then
        assertThat(textParts).hasSize(2);
        assertThat(contentOf(textParts, "text/plain")).isEqualTo(TEXT_BODY);
        assertThat(contentOf(textParts, "text/html")).isEqualTo(HTML_BODY);
        for (Part part : textParts) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
        }
    }

    @Test
    @DisplayName("SMTP 서버에 연결할 수 없으면 호출자는 EmailSendException을 받는다")
    // 서버를 멈춰 연결이 거부되는 상황을 만든다. 호출자가 Spring Mail 예외 타입을 몰라도 실패를 확인할 수 있는지 본다
    void throwsEmailSendExceptionWhenServerIsDown() {
        // given
        ApplicationContextRunner contextRunner = contextRunner();
        greenMail.stop();

        // when & then
        contextRunner.run(context -> assertThatThrownBy(() -> context.getBean(EmailSender.class).send(message()))
                .isInstanceOf(EmailSendException.class)
                .hasCauseInstanceOf(MailSendException.class)
                .hasRootCauseInstanceOf(ConnectException.class)
                .hasMessageNotContainingAny(TO, "482913"));
    }

    private static EmailMessage message() {
        return new EmailMessage(TO, SUBJECT, HTML_BODY, TEXT_BODY);
    }

    private static MimeMessage onlyReceived() {
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    private static Object contentOf(List<Part> parts, String mimeType) throws Exception {
        for (Part part : parts) {
            if (part.isMimeType(mimeType)) {
                return part.getContent();
            }
        }
        return null;
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

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(SmtpEmailSender.class)
                .withPropertyValues(
                        "spring.mail.host=127.0.0.1",
                        "spring.mail.port=" + greenMail.getSmtp().getPort(),
                        "grab.mail.provider=smtp",
                        "grab.mail.from=" + FROM);
    }
}
