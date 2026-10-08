package org.example.grab.domain.mail.config;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.mail.service.EmailSender;
import org.example.grab.domain.mail.service.SmtpEmailSender;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/*
    WISH 알림(GR-69)의 발송처를 설정에서 읽어 만든다.

    인증 코드 메일은 기존 발송기(SmtpEmailSender 빈, spring.mail 설정)를 그대로 쓰고, 알림만 여기서 만든 발송처로 보낸다.
    두 경로가 계정을 공유하지 않으므로 알림이 아무리 많아도 인증 코드의 일일 한도를 먹지 않는다.
    인증 코드가 끊기면 회원가입이 막히는 반면 알림은 못 가도 서비스가 멈추지 않아, 치명도가 다른 두 경로를 분리한다.

    host가 비어 있으면 기본 발송기를 그대로 돌려준다. 덕분에 설정을 건드리지 않은 로컬 개발 환경에서는
    알림도 Mailpit으로 나가고, 알림 발송에 별도 설정이 필요 없다.

    defaultCandidate = false로 등록해 EmailSender를 타입으로 주입하는 쪽(인증 코드 경로)이 이 빈을 집지 않게 한다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NoticeMailConfig.NoticeMailProperties.class)
public class NoticeMailConfig {

    public static final String NOTICE_EMAIL_SENDER = "noticeEmailSender";

    // SMTP 장애 시 발송 작업이 무한 대기하지 않도록 제한한다(application-mail.yml의 인증 코드 설정과 같은 값)
    private static final String TIMEOUT_MILLIS = "5000";

    /**
     * 알림 발송처의 SMTP 접속 정보.
     *
     * @param from 이 발송처가 쓸 발신 주소. 발송처마다 검증된 주소가 달라 공용 grab.mail.from과 따로 둔다.
     */
    @ConfigurationProperties("grab.mail.notice")
    public record NoticeMailProperties(
            String host,
            int port,
            String username,
            String password,
            String from
    ) {
    }

    @Bean(name = NOTICE_EMAIL_SENDER, defaultCandidate = false)
    public EmailSender noticeEmailSender(NoticeMailProperties properties, EmailSender defaultEmailSender) {
        if (properties.host() == null || properties.host().isBlank()) {
            log.info("알림 메일 발송처가 설정되지 않아 기본 발송기를 사용한다");
            return defaultEmailSender;
        }
        // 접속 정보는 남기지 않는다(NFR-011과 같은 방침)
        log.info("알림 메일 발송처 등록: host={}", properties.host());
        return new SmtpEmailSender(mailSender(properties), properties.from());
    }

    private static JavaMailSender mailSender(NoticeMailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setUsername(properties.username());
        sender.setPassword(properties.password());
        Properties mailProperties = sender.getJavaMailProperties();
        mailProperties.put("mail.smtp.auth", "true");
        // required를 함께 켜야 서버가 STARTTLS를 제공하지 않을 때 평문으로 내려가 계정 정보를 보내지 않는다
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        mailProperties.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        mailProperties.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        // 알림은 수신자를 BCC로 묶어 보낸다. 기본값(false)이면 주소 하나가 거부될 때 그 묶음 전원에게 발송되지 않는다
        mailProperties.put("mail.smtp.sendpartial", "true");
        return sender;
    }
}
