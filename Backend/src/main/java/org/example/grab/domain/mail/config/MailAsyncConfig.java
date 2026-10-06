package org.example.grab.domain.mail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
    메일 발송 전용 스레드 풀. SMTP가 느리거나 멈춰도 요청 스레드와 다른 비동기 작업의 스레드를 붙잡지 않게 분리한다.
    defaultCandidate = false로 등록해 Boot 기본 executor(applicationTaskExecutor)가 그대로 만들어지게 하고,
    이 풀은 @Async(MailAsyncConfig.MAIL_TASK_EXECUTOR)처럼 이름으로 지정한 작업만 사용한다.
    @Async 활성화(@EnableAsync)는 애플리케이션 전체 설정이라 global.config.AsyncConfig에 둔다.
 */
@Configuration
public class MailAsyncConfig {

    public static final String MAIL_TASK_EXECUTOR = "mailTaskExecutor";

    // 동시에 여는 SMTP 연결 수. 크게 늘리면 Gmail이 비정상 활동으로 판단해 계정을 잠글 수 있어 적게 둔다
    private static final int MAIL_POOL_SIZE = 4;

    // 큐에 들어간 메일이 발송을 시작하기까지의 최대 대기. 재발송 간격(60초)을 넘기면 사용자가 재요청해 먼저 보낸 코드가 무효가 되므로 그보다 짧게 둔다
    private static final int MAIL_MAX_WAIT_SECONDS = 30;

    // 메일 한 통 발송 시간의 가정값. 실제 Gmail 발송 시간을 측정하면 이 값을 바꾼다
    private static final int MAIL_SEND_SECONDS = 3;

    // 최대 대기 안에 처리할 수 있는 만큼만 받는다(4 × 30 ÷ 3 = 40). 넘치면 TaskRejectedException으로 거부하고, 사용자는 재발송 간격 뒤 다시 요청한다
    private static final int MAIL_QUEUE_CAPACITY = MAIL_POOL_SIZE * MAIL_MAX_WAIT_SECONDS / MAIL_SEND_SECONDS;

    // 서버 종료 시 메일 작업이 끝날 기회를 주도록 최대 10초간 기다린다. 모든 메일의 발송 완료를 보장하지는 않는다
    private static final int MAIL_AWAIT_TERMINATION_SECONDS = 10;

    @Bean(name = MAIL_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 큐가 찰 때까지 max는 늘어나지 않으므로 core와 max를 같게 둔다
        executor.setCorePoolSize(MAIL_POOL_SIZE);
        executor.setMaxPoolSize(MAIL_POOL_SIZE);
        executor.setQueueCapacity(MAIL_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(MAIL_AWAIT_TERMINATION_SECONDS);
        return executor;
    }
}
