package org.example.grab.domain.demand.embedding;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

/*
    DROP 임베딩 생성 전용 스레드 풀과 Gemini 임베딩 연결.
    - 풀: 공개 직후 생성을 공개 요청과 분리한다. 생성은 공개 수만큼만 생기고 늦어져도 배치가 다시 채우므로
      작게 두고, 큐가 차면 그 DROP은 다음 배치로 미룬다.
    - 연결: 자동 구성의 연결(@ConditionalOnMissingBean)은 HTTP 시간 제한이 없고 SDK가 429·5xx를 최대 5번 재시도한다.
      시간 제한을 걸고 재시도를 끈 Client로 연결을 직접 등록한다. 실패는 다음 배치가 다시 시도한다.
      Client를 빈으로 등록하지 않는 것은 설명 생성용 Client 빈(DemandExplanationConfig)과 겹치지 않게 하기 위해서다.
 */
@Configuration
@EnableConfigurationProperties(DropEmbeddingProperties.class)
public class DropEmbeddingConfig {

    public static final String DROP_EMBEDDING_TASK_EXECUTOR = "dropEmbeddingTaskExecutor";

    private static final int POOL_SIZE = 1;
    private static final int QUEUE_CAPACITY = 100;

    @Bean(name = DROP_EMBEDDING_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor dropEmbeddingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("drop-embedding-");
        return executor;
    }

    // spring.ai.model.embedding.text=google-genai일 때만 만든다. none이면 임베딩 모델이 없어 생성하지 않는다
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding.text", havingValue = "google-genai")
    public GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            @Value("${spring.ai.google.genai.embedding.api-key:}") String apiKey,
            DropEmbeddingProperties properties
    ) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("spring.ai.google.genai.embedding.api-key(GEMINI_API_KEY)가 필요합니다.");
        }
        Client client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder()
                        .timeout(Math.toIntExact(properties.timeout().toMillis()))
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        return GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(apiKey)
                .genAiClient(client)
                .build();
    }
}
