package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AsyncEmailDispatcherTest {

    private static final EmailMessage MESSAGE = new EmailMessage(
            "user@example.com", "[GRAB] 이메일 인증 코드", "<p>인증 코드: 482913</p>", "인증 코드: 482913");

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
}
