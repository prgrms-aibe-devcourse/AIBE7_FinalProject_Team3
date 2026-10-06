package org.example.grab.domain.mail.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.AuthenticationFailedException;
import org.example.grab.domain.mail.config.MailAsyncConfig;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/*
    SMTP 서버가 응답을 돌려주는 실패(인증 실패)에서 예외·로그에 SMTP 비밀번호와 메일 내용이 남지 않는지 확인한다(NFR-011).
    GreenMail에 계정을 만들어 인증을 요구하고, 틀린 비밀번호로 로그인하게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailSensitiveDataGreenMailTest {

    private static final String USERNAME = "grab.team@example.com";
    private static final String PASSWORD = "correct-app-password";
    private static final String WRONG_PASSWORD = "wrong-app-password-secret";

    private static final String RECIPIENT = "user@example.com";
    private static final String CODE = "482913";
    private static final String HTML_BODY = "<p>인증 코드: " + CODE + "</p>";
    private static final EmailMessage MESSAGE =
            new EmailMessage(RECIPIENT, "[GRAB] 이메일 인증 코드", HTML_BODY, "인증 코드: " + CODE);

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(USERNAME, PASSWORD));

    @Test
    @DisplayName("올바른 계정이면 인증 후 발송된다")
    // 아래 실패 테스트가 인증 실패 때문에 실패하는 것임을 보장하기 위한 대조 테스트
    void deliversWithCorrectPassword() {
        // given & when
        contextRunner(PASSWORD).run(context -> context.getBean(EmailSender.class).send(MESSAGE));

        // then
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("인증에 실패하면 EmailSendException의 스택 트레이스에 비밀번호·인증 코드·본문이 없다")
    void excludesSecretsFromAuthenticationFailure() {
        contextRunner(WRONG_PASSWORD).run(context -> {
            // when
            EmailSendException exception = catchThrowableOfType(
                    EmailSendException.class, () -> context.getBean(EmailSender.class).send(MESSAGE));

            // then
            assertThat(exception).hasRootCauseInstanceOf(AuthenticationFailedException.class);
            assertThat(stackTraceOf(exception)).doesNotContain(WRONG_PASSWORD, PASSWORD, CODE, HTML_BODY);
        });
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("비동기 발송에서 인증에 실패하면 로그에 비밀번호·받는 주소·인증 코드가 없다")
    void excludesSecretsFromAsyncFailureLog(CapturedOutput output) {
        contextRunner(WRONG_PASSWORD).run(context -> {
            // when
            context.getBean(AsyncEmailDispatcher.class).dispatch(MESSAGE);

            // then
            awaitOutput(output, "메일 발송 실패");
            assertThat(output).contains("AuthenticationFailedException");
            assertThat(output).doesNotContain(WRONG_PASSWORD, PASSWORD, RECIPIENT, CODE);
        });
    }

    private static void awaitOutput(CapturedOutput output, String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!output.getAll().contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(output).contains(text);
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        return stackTrace.toString();
    }

    private static ApplicationContextRunner contextRunner(String password) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(MailAsyncConfig.class, SmtpEmailSender.class, AsyncEmailDispatcher.class)
                .withPropertyValues(
                        "spring.mail.host=127.0.0.1",
                        "spring.mail.port=" + greenMail.getSmtp().getPort(),
                        "spring.mail.username=" + USERNAME,
                        "spring.mail.password=" + password,
                        "spring.mail.properties.mail.smtp.auth=true",
                        "grab.mail.provider=smtp",
                        "grab.mail.from=" + USERNAME);
    }
}
