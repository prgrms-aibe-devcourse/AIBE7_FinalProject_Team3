package org.example.grab.domain.drop.aidraft;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
    사진 초안 LLM 호출 전용 스레드 풀과 ChatModel.
    - 풀: 요청 스레드가 시간 제한을 걸고 기다리도록 호출만 이 풀에서 한다. 판매자가 버튼을 눌러야 생기고
      하루 횟수도 제한하므로 크게 두지 않는다. 가득 차면 AI_DRAFT_FAILED로 응답한다.
    - ChatModel: 공용 Gemini Client는 설명 생성에 맞춘 HTTP 시간 제한(9초)이라 이미지 요청에는 짧다.
      HTTP 시간 제한을 서비스 시간 제한보다 짧게 건 전용 Client로 따로 만들어, 시간 제한을 넘긴 요청이
      작업자를 붙잡지 않게 한다. 모델·온도 등 기본 옵션과 재시도 설정은 공용 ChatModel을 그대로 따른다.
 */
@Configuration
@EnableConfigurationProperties(DropAiDraftProperties.class)
public class DropAiDraftConfig {

    public static final String DROP_AI_DRAFT_TASK_EXECUTOR = "dropAiDraftTaskExecutor";

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 10;

    /*
        사진 초안 전용 ChatModel. ChatModel 타입 빈으로 등록하면 자동 구성(@ConditionalOnMissingBean)이
        수요 분석 설명용 ChatModel을 만들지 않으므로 이 타입으로 감싸 등록한다.
     */
    public record DraftChatModel(GoogleGenAiChatModel chatModel) {
    }

    @Bean(name = DROP_AI_DRAFT_TASK_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor dropAiDraftTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("drop-ai-draft-");
        return executor;
    }

    // spring.ai.model.chat=google-genai일 때만 만든다. 키 검사는 공용 Client(DemandExplanationConfig)가 한다
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
    public DraftChatModel dropAiDraftChatModel(
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            GoogleGenAiChatModel chatModel,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ObservationRegistry> observationRegistry,
            DropAiDraftProperties properties
    ) {
        Client client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder()
                        .timeout(Math.toIntExact(properties.httpTimeout().toMillis()))
                        .build())
                .build();
        return new DraftChatModel(GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(chatModel.getOptions())
                .retryTemplate(retryTemplate.getIfUnique(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE))
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build());
    }
}
