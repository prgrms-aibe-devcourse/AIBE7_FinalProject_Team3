package org.example.grab.domain.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/*
    JavaMailSender로 메일 한 통을 SMTP 발송한다. 접속 정보·타임아웃은 application-mail.yml의 spring.mail 설정을 따른다.
    HTML과 텍스트 본문을 multipart/alternative로 함께 보내 HTML을 표시하지 못하는 클라이언트도 내용을 읽게 한다.
 */
@Service
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender; // 메일 전송에 사용되는 객체
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${grab.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        // 메시지 구성 중 발생한 MessagingException도 JavaMailSender가 MailException으로 바꿔 던진다
        // JavaMailSender.send() 가 만든 MimeMessage에 prepare()가 내용을 채우고, send() 직접 해당 객체 전송
        mailSender.send(mimeMessage -> prepare(mimeMessage, message));
    }

    // MimeMessage -> 발신자·수신자·제목·본문·첨부파일 등의 메일 정보 보관
    private void prepare(MimeMessage mimeMessage, EmailMessage message) throws MessagingException {
        // 한글 제목·본문이 깨지지 않도록 헤더와 본문 인코딩을 UTF-8로 고정한다
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.textBody(), message.htmlBody()); // 수신 메일 프로그램이 HTML을 지원하면 HTML을, 지원하지 않으면 텍스트
    }
}
