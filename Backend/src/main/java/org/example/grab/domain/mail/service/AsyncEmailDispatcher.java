package org.example.grab.domain.mail.service;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.mail.config.MailAsyncConfig;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/*
    메일 발송을 메일 전용 스레드 풀에 넘기는 비동기 진입점. 호출한 스레드는 발송 완료를 기다리지 않는다.
    @Async 대신 executor에 직접 넘긴다. 프록시를 거치지 않으므로 같은 객체 안에서 호출해도 비동기가 빠지지 않고,
    큐가 차서 거부되는 경우(TaskRejectedException)도 호출하는 쪽이 아닌 이 클래스에서 처리할 수 있다.
    트랜잭션 커밋 후·응답 후에 호출하는 시점은 호출하는 쪽(GR-61)이 정한다.
    큐 거부와 발송 실패는 로그만 남기고 호출하는 쪽에 던지지 않는다. 발송 실패가 코드 요청의 응답을 바꾸지 않고,
    사용자는 재발송 간격 뒤 다시 요청한다(MEMBER_AUTH 1.2.1). 재시도·요청 제한은 호출하는 쪽의 책임이다.
 */
@Slf4j
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
        try {
            mailTaskExecutor.execute(() -> send(message));
        } catch (TaskRejectedException e) {
            // 큐가 찼다는 것은 SMTP가 느리거나 요청이 몰렸다는 뜻이다. 쌓아 두면 재발송 간격보다 늦게 도착하므로 버린다
            log.warn("메일 발송 대기열이 가득 차 발송하지 않음: {}", message);
        }
    }

    // 메일 전용 스레드 풀에서 실행된다. 예외를 밖으로 던지면 스레드의 기본 처리기가 cause 전체를 출력하므로 여기서 끝낸다
    private void send(EmailMessage message) {
        try {
            emailSender.send(message);
        } catch (EmailSendException e) {
            // SMTP 서버 응답 문구에 받는 주소가 들어갈 수 있어 스택 트레이스·예외 메시지 대신 원인 예외 이름만 남긴다
            log.warn("메일 발송 실패: {}, cause={}", message, causeNames(e));
        } catch (RuntimeException e) {
            log.error("메일 발송 중 예상하지 못한 오류: {}", message, e);
        }
    }

    private static String causeNames(Throwable throwable) {
        List<String> names = new ArrayList<>();
        for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
            names.add(cause.getClass().getSimpleName());
        }
        // 예외 클래스 이름을 모아 문자열로 반환
        return String.join(" > ", names);
    }
}
