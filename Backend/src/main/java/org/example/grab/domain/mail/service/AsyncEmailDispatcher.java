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

    // 청크당 최소 통수. 이보다 적게 쪼개면 SMTP 연결만 늘고 전체 시간은 줄지 않는다
    private static final int MIN_MESSAGES_PER_CHUNK = 25;

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
            log.warn("[AsyncEmailDispatcher.dispatch]메일 발송 대기열이 가득 차 발송하지 않음: {}", message);
        }
    }

    /*
        한 번의 알림으로 수신자가 여럿일 때 쓴다(GR-69). 통당 dispatch()를 호출하면 수신자 수가
        큐 용량(MailAsyncConfig 기준 40)을 넘는 순간부터 조용히 버려지므로, 목록을 작업 단위로 묶어 넘긴다.
        발송은 EmailSender.sendAll에 맡겨 작업 하나가 SMTP 연결 하나만 쓰게 한다.

        수신자가 많으면 메일 풀 크기만큼 청크로 나눠 동시에 보낸다. 한 스레드로 보내면 마지막 수신자가
        '수신자 수 × 통당 전송 시간'만큼 늦는데, 판매 시작 알림은 전원 도착까지의 시간이 요구사항이다.
        동시 연결이 풀 크기(4)까지 늘지만 이는 MailAsyncConfig가 Gmail을 전제로 정해 둔 상한 그대로다.

        큐가 차서 제출하지 못한 청크는 버리지 않고 첫 작업에 합쳐 마지막에 한 번에 제출한다.
        덕분에 최악의 경우에도 '목록 전체가 작업 하나'가 되어, 나누기 전과 누락 위험이 같다.
     */
    public void dispatchAll(List<EmailMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<List<EmailMessage>> chunks = split(List.copyOf(messages));

        // 첫 청크는 마지막에 제출한다. 그 사이 거부된 청크를 여기에 모아 함께 보내기 위함이다
        List<EmailMessage> remainder = new ArrayList<>(chunks.get(0));
        for (List<EmailMessage> chunk : chunks.subList(1, chunks.size())) {
            try {
                mailTaskExecutor.execute(() -> sendAll(chunk));
            } catch (TaskRejectedException e) {
                remainder.addAll(chunk);
            }
        }

        List<EmailMessage> lastTask = List.copyOf(remainder);
        try {
            mailTaskExecutor.execute(() -> sendAll(lastTask));
        } catch (TaskRejectedException e) {
            // 받는 주소·본문은 남기지 않는다(NFR-011과 같은 방침)
            log.warn("[AsyncEmailDispatcher.dispatchAll]메일 발송 대기열이 가득 차 발송하지 않음: {}통", lastTask.size());
        }
    }

    /*
        목록을 메일 풀 크기만큼의 청크로 나눈다. 청크가 너무 잘게 쪼개지면 연결 비용만 늘고 이득이 없으므로,
        청크당 최소 통수를 채울 수 있을 때만 나눈다. 수신자가 적으면 나누지 않고 연결 하나로 보낸다.
     */
    private static List<List<EmailMessage>> split(List<EmailMessage> messages) {
        int chunkCount = Math.min(MailAsyncConfig.MAIL_POOL_SIZE, messages.size() / MIN_MESSAGES_PER_CHUNK);
        if (chunkCount <= 1) {
            return List.of(messages);
        }
        int chunkSize = (messages.size() + chunkCount - 1) / chunkCount;
        List<List<EmailMessage>> chunks = new ArrayList<>();
        for (int start = 0; start < messages.size(); start += chunkSize) {
            chunks.add(messages.subList(start, Math.min(start + chunkSize, messages.size())));
        }
        return chunks;
    }

    // send()와 같은 이유로 예외를 메일 풀 스레드 밖으로 내보내지 않는다. 실패한 주소는 로그에 남기지 않는다
    private void sendAll(List<EmailMessage> batch) {
        try {
            emailSender.sendAll(batch);
        } catch (EmailSendException e) {
            // 일부만 실패해도 여기로 온다. 몇 통이 실패했는지는 예외에 없으므로 묶음 크기만 남긴다
            log.warn("[AsyncEmailDispatcher.sendAll]묶음 메일 발송 실패: {}통, cause={}", batch.size(), causeNames(e));
        } catch (RuntimeException e) {
            log.error("[AsyncEmailDispatcher.sendAll]묶음 메일 발송 중 예상하지 못한 오류: {}통", batch.size(), e);
        }
    }

    // 메일 전용 스레드 풀에서 실행된다. 예외를 밖으로 던지면 스레드의 기본 처리기가 cause 전체를 출력하므로 여기서 끝낸다
    private void send(EmailMessage message) {
        try {
            emailSender.send(message);
        } catch (EmailSendException e) {
            // SMTP 서버 응답 문구에 받는 주소가 들어갈 수 있어 스택 트레이스·예외 메시지 대신 원인 예외 이름만 남긴다
            log.warn("[AsyncEmailDispatcher.send]메일 발송 실패: {}, cause={}", message, causeNames(e));
        } catch (RuntimeException e) {
            log.error("[AsyncEmailDispatcher.send]메일 발송 중 예상하지 못한 오류: {}", message, e);
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
