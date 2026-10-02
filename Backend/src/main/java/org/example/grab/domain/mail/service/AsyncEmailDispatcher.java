package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.config.MailAsyncConfig;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/*
    메일 발송을 메일 전용 스레드 풀에 넘기는 비동기 진입점. 호출한 스레드는 발송 완료를 기다리지 않는다.
    @Async 대신 executor에 직접 넘긴다. 프록시를 거치지 않으므로 같은 객체 안에서 호출해도 비동기가 빠지지 않고,
    큐가 차서 거부되는 경우(TaskRejectedException)도 호출하는 쪽이 아닌 이 클래스에서 처리할 수 있다.
    트랜잭션 커밋 후·응답 후에 호출하는 시점은 호출하는 쪽(GR-61)이 정한다.
 */
@Service
public class AsyncEmailDispatcher {

    private final EmailSender emailSender;
    private final TaskExecutor mailTaskExecutor;

    // 메일 풀은 defaultCandidate = false로 등록되어 타입만으로는 주입되지 않으므로 이름으로 지정한다
    public AsyncEmailDispatcher(
            EmailSender emailSender,
            @Qualifier(MailAsyncConfig.MAIL_TASK_EXECUTOR) TaskExecutor mailTaskExecutor
    ) {
        this.emailSender = emailSender;
        this.mailTaskExecutor = mailTaskExecutor;
    }

    public void dispatch(EmailMessage message) {
        mailTaskExecutor.execute(() -> emailSender.send(message));
    }
}
