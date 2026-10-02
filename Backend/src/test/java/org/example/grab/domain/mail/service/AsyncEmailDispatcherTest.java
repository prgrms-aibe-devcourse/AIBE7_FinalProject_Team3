package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class AsyncEmailDispatcherTest {

    private static final String RECIPIENT = "user@example.com";

    private static final String CODE = "482913";

    private static final EmailMessage MESSAGE = new EmailMessage(
            RECIPIENT, "[GRAB] 이메일 인증 코드", "<p>인증 코드: " + CODE + "</p>", "인증 코드: " + CODE);

    @Test
    @DisplayName("발송을 호출한 스레드에서 하지 않고 메일 executor에 넘긴다")
    // 호출한 스레드가 SMTP 발송을 기다리지 않는지, executor가 작업을 실행해야 발송되는지 확인하는 테스트
    void submitsSendToMailExecutor() {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = submitted::add;
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);

        // when
        dispatcher.dispatch(MESSAGE);

        // then
        verifyNoInteractions(emailSender);
        submitted.forEach(Runnable::run);
        verify(emailSender).send(MESSAGE);
    }

    @Test
    @DisplayName("큐가 가득 차 거부되면 호출한 쪽에 예외를 던지지 않고 경고 로그를 남긴다")
    // 발송 실패가 코드 요청의 응답을 바꾸지 않아야 하므로(MEMBER_AUTH 1.2.1) 거부가 호출자에게 새지 않는지 확인하는 테스트
    void logsAndSwallowsRejection(CapturedOutput output) {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new TaskRejectedException("메일 풀 대기열 초과")).when(executor).execute(any(Runnable.class));
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);

        // when & then
        assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();
        verifyNoInteractions(emailSender);
        assertThat(output).contains("WARN", "메일 발송 대기열이 가득 차 발송하지 않음");
        assertThat(output).doesNotContain(RECIPIENT, CODE);
    }

    @Test
    @DisplayName("발송에 실패하면 원인 예외 이름만 경고 로그로 남기고 받는 주소·인증 코드는 남기지 않는다")
    // SMTP 수신 거부 응답처럼 원인 예외 메시지에 받는 주소가 들어 있어도 로그에 나오지 않는지 확인하는 테스트
    void logsSendFailureWithoutSensitiveData(CapturedOutput output) {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        doThrow(new EmailSendException(new MailSendException("550 <" + RECIPIENT + ">: Recipient address rejected")))
                .when(emailSender).send(MESSAGE);
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, new SyncTaskExecutor());

        // when & then
        assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();
        assertThat(output).contains("WARN", "메일 발송 실패", "cause=MailSendException");
        assertThat(output).doesNotContain(RECIPIENT, CODE, "Recipient address rejected");
    }

    @Test
    @DisplayName("발송 중 예상하지 못한 오류가 나도 풀 스레드 밖으로 던지지 않고 오류 로그를 남긴다")
    // 처리하지 않은 예외는 스레드 기본 처리기가 로그 설정 밖에서 출력하므로 로그로 남기는지 확인하는 테스트
    void logsUnexpectedFailure(CapturedOutput output) {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        doThrow(new IllegalStateException("예상하지 못한 오류")).when(emailSender).send(MESSAGE);
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, new SyncTaskExecutor());

        // when & then
        assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();
        assertThat(output).contains("ERROR", "메일 발송 중 예상하지 못한 오류", "IllegalStateException");
        assertThat(output).doesNotContain(RECIPIENT, CODE);
    }
}
