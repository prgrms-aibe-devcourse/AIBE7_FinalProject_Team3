package org.example.grab.domain.mail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
    메일 발송 전용 스레드 풀. SMTP가 느리거나 멈춰도 요청 스레드와 다른 비동기 작업의 스레드를 붙잡지 않게 분리한다.
    인증 코드(Gmail)와 알림(Brevo)은 풀도 나눈다. 전에는 하나의 풀을 같이 썼는데, 알림 배치가 한 번에 여럿
    몰리면(세일 오픈이 겹치는 시간대 등) 그 풀을 전부 차지해 인증 코드 발송이 밀리거나 거부될 수 있었다.
    풀을 나누면 한쪽이 몰려도 다른 쪽 스레드·큐에 전혀 영향을 주지 않는다.
    defaultCandidate = false로 등록해 Boot 기본 executor(applicationTaskExecutor)가 그대로 만들어지게 하고,
    이 풀들은 @Async(MailAsyncConfig.AUTH_MAIL_TASK_EXECUTOR)처럼 이름으로 지정한 작업만 사용한다.
    @Async 활성화(@EnableAsync)는 애플리케이션 전체 설정이라 global.config.AsyncConfig에 둔다.
 */
@Configuration
public class MailAsyncConfig {

    public static final String AUTH_MAIL_TASK_EXECUTOR = "authMailTaskExecutor";
    public static final String NOTICE_MAIL_TASK_EXECUTOR = "noticeMailTaskExecutor";

    // 서버 종료 시 메일 작업이 끝날 기회를 주도록 최대 10초간 기다린다. 모든 메일의 발송 완료를 보장하지는 않는다
    private static final int AWAIT_TERMINATION_SECONDS = 10;

    /*
       동시에 여는 SMTP 연결 수. 크게 늘리면 Gmail이 비정상 활동으로 판단해 계정을 잠글 수 있어 적게 둔다.
       인증 코드는 작업 하나가 메일 한 통이라, 동시 요청 수만큼 작업이 쌓인다.
     */
    private static final int AUTH_POOL_SIZE = 4;

    // 큐에 들어간 메일이 발송을 시작하기까지의 최대 대기. 재발송 간격(60초)을 넘기면 사용자가 재요청해 먼저 보낸 코드가 무효가 되므로 그보다 짧게 둔다
    private static final int AUTH_MAX_WAIT_SECONDS = 30;

    // 메일 한 통 발송 시간의 가정값. 실제 Gmail 발송 시간을 측정하면 이 값을 바꾼다
    private static final int AUTH_SEND_SECONDS = 3;

    /*
       최대 대기 안에 처리할 수 있는 만큼만 받는다(4 × 30 ÷ 3 = 40). 넘치면 TaskRejectedException으로 거부하고,
       사용자는 재발송 간격 뒤 다시 요청한다. 거부해도 괜찮은 이유는 사용자가 재시도할 수 있기 때문이다(MEMBER_AUTH 1.2.1).
     */
    private static final int AUTH_QUEUE_CAPACITY = AUTH_POOL_SIZE * AUTH_MAX_WAIT_SECONDS / AUTH_SEND_SECONDS;

    /*
       알림은 작업 하나가 DROP 하나(수신자 수백 명을 BCC로 묶음)라, 동시에 전환되는 DROP 수만큼만 작업이 생긴다.
       그 수는 거의 항상 1~2개라 스레드 1개로 충분하고, Brevo에 동시에 여는 연결도 최소화된다.
     */
    private static final int NOTICE_POOL_SIZE = 1;

    /*
       ponytail: 세일 오픈이 한 스케줄러 tick에 몰리는 극단적인 경우를 대비한 여유값이고 실측 기반은 아니다.
       인증 코드와 달리 알림은 큐 거부 시 재시도 경로가 없어 그대로 유실되므로(AsyncEmailDispatcher.submit),
       넉넉하게 잡아 스레드 1개가 순차 처리하는 지연을 택하고 유실을 피한다. 실제 피크 작업 수를 측정하면 이 값을 바꾼다.
     */
    private static final int NOTICE_QUEUE_CAPACITY = 100;

    @Bean(name = AUTH_MAIL_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor authMailTaskExecutor() {
        return mailTaskExecutor(AUTH_POOL_SIZE, AUTH_QUEUE_CAPACITY, "mail-auth-");
    }

    @Bean(name = NOTICE_MAIL_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor noticeMailTaskExecutor() {
        return mailTaskExecutor(NOTICE_POOL_SIZE, NOTICE_QUEUE_CAPACITY, "mail-notice-");
    }

    private static ThreadPoolTaskExecutor mailTaskExecutor(int poolSize, int queueCapacity, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 큐가 찰 때까지 max는 늘어나지 않으므로 core와 max를 같게 둔다
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        return executor;
    }
}
