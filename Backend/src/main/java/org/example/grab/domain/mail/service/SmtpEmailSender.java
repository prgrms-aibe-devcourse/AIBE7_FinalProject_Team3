package org.example.grab.domain.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/*
    JavaMailSender로 메일 한 통을 SMTP 발송한다. 접속 정보·타임아웃은 application-mail.yml의 spring.mail 설정을 따른다.
    HTML과 텍스트 본문을 multipart/alternative로 함께 보내 HTML을 표시하지 못하는 클라이언트도 내용을 읽게 한다.
    실패해도 재시도하지 않고 EmailSendException을 던진다. 재시도·요청 제한·메트릭은 호출하는 쪽에서 다룬다(EmailSender 계약).
    grab.mail.provider=smtp일 때만 등록한다. matchIfMissing을 두지 않아 값이 없거나 잘못되면 EmailSender를 주입하는 쪽에서 기동이 실패하고, 메일 발송이 조용히 꺼지지 않는다.
 */
@Service
@ConditionalOnProperty(name = "grab.mail.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender; // 메일 전송에 사용되는 객체
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${grab.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        // 메시지 구성 중 발생한 MessagingException도 JavaMailSender가 MailException으로 바꿔 던지므로 MailException만 감싼다
        // JavaMailSender.send() 가 만든 MimeMessage에 prepare()가 내용을 채우고, send() 직접 해당 객체 전송
        try {
            mailSender.send(mimeMessage -> prepare(mimeMessage, message));
        } catch (MailException e) {
            throw new EmailSendException(e);
        }
    }

    /*
        연결 하나로 목록 전체를 보낸다. JavaMailSenderImpl은 preparator 배열을 받으면 Transport를 한 번만 열고
        모든 메일을 그 연결로 보낸다. 통마다 send()를 부르면 접속·STARTTLS·인증을 수신자 수만큼 반복해,
        Gmail처럼 연결 비용이 큰 서버에서는 이 반복이 전체 발송 시간의 대부분을 차지한다.
        일부 주소가 거부되면 JavaMail이 나머지를 계속 보낸 뒤 MailSendException으로 모아 던지므로,
        이 구현은 EmailSender 계약(일부 실패해도 계속, 마지막에 한 번 던짐)을 그대로 만족한다.
     */
    @Override
    public void sendAll(List<EmailMessage> messages) {
        MimeMessagePreparator[] preparators = messages.stream()
                .map(message -> (MimeMessagePreparator) mimeMessage -> prepare(mimeMessage, message))
                .toArray(MimeMessagePreparator[]::new);
        try {
            mailSender.send(preparators);
        } catch (MailException e) {
            throw new EmailSendException(e);
        }
    }

    // MimeMessage -> 발신자·수신자·제목·본문·첨부파일 등의 메일 정보 보관
    private void prepare(MimeMessage mimeMessage, EmailMessage message) throws MessagingException {
        // 한글 제목·본문이 깨지지 않도록 헤더와 본문 인코딩을 UTF-8로 고정한다
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        // BCC 전용 메일은 받는 사람 칸이 비면 클라이언트가 수신자를 표시하지 못하므로 발신 주소를 넣는다
        helper.setTo(message.to() != null ? message.to() : from);
        if (!message.bcc().isEmpty()) {
            helper.setBcc(message.bcc().toArray(String[]::new));
        }
        helper.setSubject(message.subject());
        helper.setText(message.textBody(), message.htmlBody()); // 수신 메일 프로그램이 HTML을 지원하면 HTML을, 지원하지 않으면 텍스트
    }
}
