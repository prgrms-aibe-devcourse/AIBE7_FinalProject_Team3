package org.example.grab.domain.mail.service;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    @Test
    @DisplayName("발송 중 MailException이 발생하면 재시도하지 않고 EmailSendException으로 감싸 던진다")
    // 호출하는 쪽이 Spring Mail 예외 타입을 몰라도 실패를 확인할 수 있는지, 재시도는 구현체 밖의 책임인지 확인하는 테스트
    void wrapsMailExceptionWithEmailSendException() {
        // given
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MailSendException cause = new MailSendException("SMTP 응답 오류");
        doThrow(cause).when(mailSender).send(any(MimeMessagePreparator.class));
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM);

        // when & then
        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(EmailSendException.class)
                .hasCause(cause)
                .hasMessageNotContainingAny("user@example.com", "482913");
        verify(mailSender).send(any(MimeMessagePreparator.class));
    }

    @Test
    @DisplayName("SMTP 서버에 연결할 수 없으면 EmailSendException을 던진다")
    // 실제 JavaMailSenderImpl로 닫힌 포트에 접속해, 연결 실패가 Spring Mail 예외로 새지 않는지 확인하는 테스트
    void throwsEmailSendExceptionWhenConnectionFails() throws Exception {
        // given
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(closedPort());
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM);

        // when & then
        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(EmailSendException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("발송 실패 예외의 스택 트레이스에 본문·인증 코드·SMTP 비밀번호가 없다")
    // 호출하는 쪽이 log.error("...", e)로 남기면 cause까지 출력되므로, 그 출력 전체에 민감정보가 없는지 확인하는 테스트
    void excludesSecretsFromFailureStackTrace() throws Exception {
        // given
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(closedPort());
        mailSender.setUsername("grab.team@example.com");
        mailSender.setPassword("app-password-secret");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM);

        // when & then
        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(EmailSendException.class)
                .satisfies(e -> assertThat(stackTraceOf(e)).doesNotContain("482913", "<p>", "app-password-secret"));
    }

    @Test
    @DisplayName("메일 구성 중 실패해도 EmailSendException을 던진다")
    // 잘못된 주소로 MessagingException이 나면 JavaMailSender가 MailParseException으로 바꾸고, 이것도 감싸는지 확인하는 테스트
    void throwsEmailSendExceptionWhenPreparationFails() {
        // given
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "잘못된 주소 <");

        // when & then
        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(EmailSendException.class)
                .hasCauseInstanceOf(MailParseException.class);
    }

    @Test
    @DisplayName("grab.mail.provider가 smtp이면 SMTP 구현체를 EmailSender로 등록한다")
    void registersWhenProviderIsSmtp() {
        // given & when & then
        contextRunner()
                .withPropertyValues("grab.mail.provider=smtp")
                .run(context -> assertThat(context).getBean(EmailSender.class).isInstanceOf(SmtpEmailSender.class));
    }

    @Test
    @DisplayName("grab.mail.provider가 smtp가 아니거나 없으면 SMTP 구현체를 등록하지 않는다")
    // 기본값으로 등록되면 다른 구현체로 전환할 때 EmailSender 빈이 둘이 되므로, 명시한 값에서만 등록되는지 확인하는 테스트
    void skipsWhenProviderIsNotSmtp() {
        // given & when & then
        contextRunner()
                .withPropertyValues("grab.mail.provider=ses")
                .run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
        contextRunner()
                .run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        return stackTrace.toString();
    }

    private static EmailMessage message() {
        return new EmailMessage("user@example.com", "[GRAB] 이메일 인증 코드", "<p>인증 코드: 482913</p>", "인증 코드: 482913");
    }

    // 잠깐 열었다 닫은 포트라 접속하면 바로 연결이 거부된다
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .withPropertyValues("grab.mail.from=" + FROM)
                .withUserConfiguration(SmtpEmailSender.class);
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
