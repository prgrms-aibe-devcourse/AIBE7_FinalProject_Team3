package org.example.grab.domain.demand.explanation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
    LLM 호출 전용 스레드 풀. 응답이 느려도 요청 스레드와 다른 비동기 작업(메일 등)을 붙잡지 않게 분리한다.
    계획 저장은 판매자가 직접 누를 때만 생겨 동시 호출이 많지 않다. 큐가 차면 거부하고 설명 상태를 FAILED로 남긴다
    (계획 저장·조회는 설명과 관계없이 동작한다, DEMAND.md 3절).
    defaultCandidate = false로 등록해 Boot 기본 executor(applicationTaskExecutor)가 그대로 만들어지게 한다.
 */
@Configuration
@EnableConfigurationProperties(DemandExplanationProperties.class)
public class DemandExplanationConfig {

    public static final String DEMAND_EXPLANATION_TASK_EXECUTOR = "demandExplanationTaskExecutor";

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 20;
    // 서버 종료 시 진행 중인 호출(최대 10초)이 끝날 기회를 준다. 못 끝낸 계획은 2분 뒤 응답에서 FAILED로 보인다
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
