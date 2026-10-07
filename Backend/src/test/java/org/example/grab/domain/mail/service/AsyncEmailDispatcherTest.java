package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.config.MailAsyncConfig;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class AsyncEmailDispatcherTest {

    private static final String RECIPIENT = "user@example.com";

    private static final String CODE = "482913";

    private static final EmailMessage MESSAGE = new EmailMessage(
            RECIPIENT, "[GRAB] 이메일 인증 코드", "<p>인증 코드: " + CODE + "</p>", "인증 코드: " + CODE);

    private static final EmailMessage OTHER_MESSAGE = new EmailMessage(
            "other@example.com", "[GRAB] 한정판 스니커즈 판매가 시작됐습니다", "<p>판매 시작</p>", "판매 시작");

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

    @Test
    @DisplayName("여러 통을 넘기면 큐 슬롯 하나만 쓰는 한 작업으로 묶어 sendAll에 넘긴다")
    // 수신자 수만큼 작업을 만들면 큐 용량(40)을 넘는 분량이 조용히 버려지고(GR-69),
    // 통마다 send()를 부르면 구현체가 연결을 재사용하지 못하므로 한 작업·한 번의 sendAll인지 확인하는 테스트
    void submitsAllMessagesAsSingleTask() {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = submitted::add;
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);
        List<EmailMessage> messages = List.of(MESSAGE, OTHER_MESSAGE);

        // when
        dispatcher.dispatchAll(messages);

        // then
        assertThat(submitted).hasSize(1);
        verifyNoInteractions(emailSender);
        submitted.forEach(Runnable::run);
        verify(emailSender).sendAll(messages);
        verify(emailSender, never()).send(any(EmailMessage.class));
    }

    @Test
    @DisplayName("묶음 발송이 실패해도 호출한 쪽에 던지지 않고 원인 예외 이름만 경고 로그로 남긴다")
    // 일부 주소가 거부되면 구현체가 EmailSendException 하나로 모아 던진다. 그 예외가 메일 풀 밖으로 새지 않는지 확인하는 테스트
    void logsAndSwallowsBatchSendFailure(CapturedOutput output) {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        doThrow(new EmailSendException(new MailSendException("550 <" + RECIPIENT + ">: Recipient address rejected")))
                .when(emailSender).sendAll(anyList());
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, new SyncTaskExecutor());

        // when & then
        assertThatCode(() -> dispatcher.dispatchAll(List.of(MESSAGE, OTHER_MESSAGE))).doesNotThrowAnyException();
        assertThat(output).contains("WARN", "묶음 메일 발송 실패: 2통", "cause=MailSendException");
        assertThat(output).doesNotContain(RECIPIENT, CODE, "Recipient address rejected");
    }

    @Test
    @DisplayName("묶음 발송도 큐 거부를 호출한 쪽에 던지지 않고 건수만 경고 로그로 남긴다")
    void logsAndSwallowsBatchRejection(CapturedOutput output) {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new TaskRejectedException("메일 풀 대기열 초과")).when(executor).execute(any(Runnable.class));
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);

        // when & then
        assertThatCode(() -> dispatcher.dispatchAll(List.of(MESSAGE, OTHER_MESSAGE))).doesNotThrowAnyException();
        verifyNoInteractions(emailSender);
        assertThat(output).contains("WARN", "메일 발송 대기열이 가득 차 발송하지 않음: 2통");
        assertThat(output).doesNotContain(RECIPIENT, CODE);
    }

    @Test
    @DisplayName("수신자가 많으면 메일 풀 크기만큼 작업으로 나눠 동시에 보내고 한 통도 빠뜨리지 않는다")
    // 한 작업으로만 보내면 마지막 수신자가 '수신자 수 × 통당 전송 시간'만큼 늦으므로(GR-69) 나눠 제출하는지 확인하는 테스트
    void splitsLargeBatchAcrossMailPool() {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = submitted::add;
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);
        List<EmailMessage> messages = messages(100);

        // when
        dispatcher.dispatchAll(messages);

        // then: 작업 수는 풀 크기까지만 늘고, 모든 통이 정확히 한 번씩 들어간다
        assertThat(submitted).hasSize(MailAsyncConfig.MAIL_POOL_SIZE);
        submitted.forEach(Runnable::run);
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailSender, times(MailAsyncConfig.MAIL_POOL_SIZE)).sendAll(captor.capture());
        assertThat(captor.getAllValues()).flatExtracting(batch -> batch)
                .containsExactlyInAnyOrderElementsOf(messages);
    }

    @Test
    @DisplayName("수신자가 적으면 나누지 않고 연결 하나로 보낸다")
    // 잘게 쪼개면 SMTP 연결만 늘고 전체 시간은 줄지 않으므로 확인하는 테스트
    void keepsSmallBatchAsSingleTask() {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        List<Runnable> submitted = new ArrayList<>();
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, submitted::add);

        // when
        dispatcher.dispatchAll(messages(20));

        // then
        assertThat(submitted).hasSize(1);
    }

    @Test
    @DisplayName("청크가 큐에 거부되면 버리지 않고 마지막 작업에 합쳐 보낸다")
    // 나눠 제출하면 일부만 거부되는 부분 실패가 생길 수 있다. 그 몫이 사라지지 않는지 확인하는 테스트
    void mergesRejectedChunksIntoLastTask() {
        // given: 처음 두 번의 제출만 거부하는 executor
        EmailSender emailSender = mock(EmailSender.class);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = task -> {
            if (submitted.size() < 2) {
                submitted.add(null); // 거부된 횟수만 센다
                throw new TaskRejectedException("메일 풀 대기열 초과");
            }
            submitted.add(task);
        };
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);
        List<EmailMessage> messages = messages(100);

        // when
        dispatcher.dispatchAll(messages);

        // then: 거부된 두 청크가 마지막 작업에 합쳐져 100통이 모두 발송된다
        submitted.stream().filter(java.util.Objects::nonNull).forEach(Runnable::run);
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailSender, atLeastOnce()).sendAll(captor.capture());
        assertThat(captor.getAllValues()).flatExtracting(batch -> batch)
                .containsExactlyInAnyOrderElementsOf(messages);
    }

    private static List<EmailMessage> messages(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new EmailMessage(
                        "buyer" + index + "@example.com", "[GRAB] 판매 시작", "<p>판매 시작</p>", "판매 시작"))
                .toList();
    }

    @Test
    @DisplayName("보낼 메일이 없으면 executor에 작업을 넘기지 않는다")
    void skipsEmptyBatch() {
        // given
        EmailSender emailSender = mock(EmailSender.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AsyncEmailDispatcher dispatcher = new AsyncEmailDispatcher(emailSender, executor);

        // when
        dispatcher.dispatchAll(List.of());

        // then
        verifyNoInteractions(executor, emailSender);
    }
}
