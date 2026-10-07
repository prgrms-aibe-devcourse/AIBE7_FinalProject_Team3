package org.example.grab;

import org.example.grab.domain.mail.service.EmailSender;
import org.example.grab.domain.mail.service.SmtpEmailSender;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/*
    SMTP 서버에 연결할 수 없어도 애플리케이션이 기동되는지 확인한다(GR-60).
    로컬에서는 Mailpit이 기본 주소(localhost:1025)에서 실행 중일 수 있으므로, 아무도 듣지 않는 포트로 바꿔 SMTP가 없는 상황을 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MailServerUnavailableStartupTests {

    @DynamicPropertySource
    static void unreachableSmtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", MailServerUnavailableStartupTests::closedPort);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("SMTP 서버에 연결할 수 없어도 기동되고 메일 발송 빈이 준비된다")
    // 기동 중 SMTP 연결을 확인하지 않으므로(spring.mail.test-connection 미설정) 메일 서버 장애가 기동 실패로 번지지 않는다
    void startsWithoutSmtpServer() {
        assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
    }

    @Test
    @DisplayName("메일 health indicator를 등록하지 않아 SMTP 장애가 health 상태에 반영되지 않는다")
    void doesNotRegisterMailHealthIndicator() {
        assertThat(context.getBeansOfType(MailHealthIndicator.class)).isEmpty();
    }

    // 잠깐 열었다 닫은 포트라 접속하면 바로 연결이 거부된다
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
