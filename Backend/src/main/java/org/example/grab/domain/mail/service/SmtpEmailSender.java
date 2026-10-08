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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
        연결 하나로 목록 전체를 보낸다. JavaMailSenderImpl은 MimeMessage 배열을 받으면 Transport를 한 번만 열고
        모든 메일을 그 연결로 보낸다. 통마다 send()를 부르면 접속·STARTTLS·인증을 수신자 수만큼 반복해,
        연결 비용이 큰 서버에서는 이 반복이 전체 발송 시간의 대부분을 차지한다.

        EmailSender 계약(일부 실패해도 계속, 마지막에 한 번 던짐)은 두 단계에서 모두 지킨다.
        메시지를 만드는 단계는 아래에서 직접 건너뛰고, 보내는 단계는 일부 주소가 거부돼도 JavaMail이
        나머지를 계속 보낸 뒤 MailSendException으로 모아 던진다.
     */
    @Override
    public void sendAll(List<EmailMessage> messages) {
        /*
         * 메시지를 하나씩 만들고 실패한 것만 건너뛴다. JavaMailSender에 MimeMessagePreparator 배열을 넘기면
         * 전부 만든 뒤에 보내므로, 주소 형식이 깨진 수신자가 한 명만 있어도 묶음 전체가 한 통도 나가지 않는다.
         * BCC로 묶은 뒤에는 메일 하나가 최대 100명이라 그 피해가 더 크다(GR-69).
         */
        List<MimeMessage> prepared = new ArrayList<>(messages.size());
        MessagingException preparationFailure = null;
        for (EmailMessage message : messages) {
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                prepare(mimeMessage, message);
                prepared.add(mimeMessage);
            } catch (MessagingException e) {
                // 어떤 주소가 실패했는지는 담지 않는다(NFR-011). 마지막 원인만 남기고 나머지 준비를 계속한다
                preparationFailure = e;
            }
        }
        try {
            if (!prepared.isEmpty()) {
                mailSender.send(prepared.toArray(MimeMessage[]::new));
            }
        } catch (MailException e) {
            throw new EmailSendException(e);
        }
        // 발송은 됐지만 만들지 못한 메일이 있으면 호출하는 쪽이 실패를 알 수 있게 여기서 던진다
        if (preparationFailure != null) {
            throw new EmailSendException(preparationFailure);
        }
    }

    // MimeMessage -> 발신자·수신자·제목·본문·첨부파일 등의 메일 정보 보관
    private void prepare(MimeMessage mimeMessage, EmailMessage message) throws MessagingException {
        // 한글 제목·본문이 깨지지 않도록 헤더와 본문 인코딩을 UTF-8로 고정한다
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        if (message.to() != null) {
            helper.setTo(message.to());
        }
        if (!message.bcc().isEmpty()) {
            helper.setBcc(message.bcc().toArray(String[]::new));
            /*
             * SMTP는 To와 BCC를 모두 수신자로 쓴다. 받는 사람 칸을 채우려고 발신 주소를 To에 넣으면
             * 발신 주소도 수신자가 돼 사본이 한 통 더 가고 발송처의 일일 한도를 메일마다 1통씩 더 쓴다.
             * 실제 주소가 없는 그룹 구문으로 표시만 채운다. 수신자가 0명이라 발송 대상에는 들어가지 않는다.
             */
            mimeMessage.setHeader("To", "undisclosed-recipients:;");
        }
        helper.setSubject(message.subject());
        helper.setText(message.textBody(), message.htmlBody()); // 수신 메일 프로그램이 HTML을 지원하면 HTML을, 지원하지 않으면 텍스트
    }
}
