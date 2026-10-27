package org.example.grab.domain.demand.explanation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
    LLM 호출 전용 스레드 풀. 응답이 느려도 요청 스레드와 다른 비동기 작업(메일 등)을 붙잡지 않게 분리한다.
    설명은 판매자가 "AI 설명 보기"를 누를 때만 요청하고 같은 입력은 저장된 설명을 다시 써 동시 호출이 많지 않다.
    큐가 차면 거부하고 FAILED로 응답한다(분석 조회는 설명과 관계없이 동작한다, DEMAND.md 3.4).
    defaultCandidate = false로 등록해 Boot 기본 executor(applicationTaskExecutor)가 그대로 만들어지게 한다.
 */
@Configuration
@EnableConfigurationProperties(DemandExplanationProperties.class)
public class DemandExplanationConfig {

    public static final String DEMAND_EXPLANATION_TASK_EXECUTOR = "demandExplanationTaskExecutor";

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 20;
    // 서버 종료 시 진행 중인 호출(최대 10초)이 끝날 기회를 준다
    private static final int AWAIT_TERMINATION_SECONDS = 10;

    @Bean(name = DEMAND_EXPLANATION_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor demandExplanationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("demand-explanation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        return executor;
    }
}
