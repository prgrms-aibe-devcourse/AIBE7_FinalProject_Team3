package org.example.grab.domain.mail.service;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.example.grab.domain.mail.config.MailAsyncConfig;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/*
    실제 메일 전용 스레드 풀(MailAsyncConfig)과 SMTP 구현체로 비동기 발송을 확인한다.
    AsyncEmailDispatcherTest는 모의 executor로 분기를 확인하고, 이 테스트는 설정된 풀에서 실제 SMTP 발송·실패 처리가 일어나는지 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AsyncEmailDispatcherGreenMailTest {

    private static final String RECIPIENT = "user@example.com";
    private static final String CODE = "482913";
    private static final EmailMessage MESSAGE = new EmailMessage(
            RECIPIENT, "[GRAB] 이메일 인증 코드", "<p>인증 코드: " + CODE + "</p>", "인증 코드: " + CODE);

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    @DisplayName("비동기 발송은 메일 전용 풀에서 실행되어 SMTP 서버에 도착한다")
    void deliversThroughMailTaskExecutor() {
        contextRunner().run(context -> {
            // given
            AsyncEmailDispatcher dispatcher = context.getBean(AsyncEmailDispatcher.class);
            ThreadPoolTaskExecutor mailExecutor =
                    context.getBean(MailAsyncConfig.MAIL_TASK_EXECUTOR, ThreadPoolTaskExecutor.class);

            // when
            dispatcher.dispatch(MESSAGE);

            // then
            assertThat(greenMail.waitForIncomingEmail(Duration.ofSeconds(10).toMillis(), 1)).isTrue();
            assertThat(greenMail.getReceivedMessages()[0].getAllRecipients())
                    .extracting(Object::toString).containsExactly(RECIPIENT);
            // 서버가 메일을 받은 뒤에도 연결 종료(QUIT)까지 작업이 이어지므로 완료될 때까지 기다린다
            assertThat(awaitCompletedTasks(mailExecutor, 1)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("SMTP 서버에 연결할 수 없으면 호출자에게 예외를 던지지 않고 메일 풀 스레드에서 경고 로그를 남긴다")
    void logsFailureOnMailThreadWithoutThrowing(CapturedOutput output) {
        // given
        ApplicationContextRunner contextRunner = contextRunner();
        greenMail.stop();

        contextRunner.run(context -> {
            // when & then
            assertThatCode(() -> context.getBean(AsyncEmailDispatcher.class).dispatch(MESSAGE))
                    .doesNotThrowAnyException();
            String failureLog = awaitLogLine(output, "메일 발송 실패");
            assertThat(failureLog).contains("WARN", "mail-", "cause=MailSendException");
            assertThat(output).doesNotContain(RECIPIENT, CODE);
        });
    }

    @Test
    @DisplayName("수신자가 메일 큐 용량을 넘어도 묶음 발송은 한 통도 누락하지 않는다")
    // 통당 작업으로 넘기면 큐 용량(40)을 넘는 분량이 거부돼 조용히 사라지므로(GR-69) 전부 도착하는지 확인하는 테스트
    void deliversBatchLargerThanQueueCapacity() {
        contextRunner().run(context -> {
            // given: 큐 용량보다 많은 수신자
            AsyncEmailDispatcher dispatcher = context.getBean(AsyncEmailDispatcher.class);
            List<EmailMessage> messages = IntStream.range(0, 50)
                    .mapToObj(index -> new EmailMessage(
                            "buyer" + index + "@example.com",
                            "[GRAB] 한정판 스니커즈 판매가 시작됐습니다",
                            "<p>판매 시작</p>",
                            "판매 시작"))
                    .toList();

            // when
            dispatcher.dispatchAll(messages);

            // then
            assertThat(greenMail.waitForIncomingEmail(Duration.ofSeconds(30).toMillis(), messages.size())).isTrue();
            assertThat(greenMail.getReceivedMessages()).hasSize(messages.size());
        });
    }

    private static long awaitCompletedTasks(ThreadPoolTaskExecutor executor, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (executor.getThreadPoolExecutor().getCompletedTaskCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return executor.getThreadPoolExecutor().getCompletedTaskCount();
    }

    private static String awaitLogLine(CapturedOutput output, String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String line = Arrays.stream(output.getAll().split("\\R"))
                    .filter(candidate -> candidate.contains(text))
                    .findFirst()
                    .orElse(null);
            if (line != null) {
                return line;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("로그가 남지 않음: " + text);
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(MailAsyncConfig.class, SmtpEmailSender.class, AsyncEmailDispatcher.class)
                .withPropertyValues(
                        "spring.mail.host=127.0.0.1",
                        "spring.mail.port=" + greenMail.getSmtp().getPort(),
                        "grab.mail.provider=smtp",
                        "grab.mail.from=no-reply@grab.local");
    }
}
