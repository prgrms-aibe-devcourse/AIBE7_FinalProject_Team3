package org.example.grab.domain.demand.embedding;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class DropEmbeddingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 앱과 같이 연결 자동 구성(GoogleGenAiEmbeddingConnectionAutoConfiguration)은 빼고 DropEmbeddingConfig의 연결만 쓴다
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class, GoogleGenAiTextEmbeddingAutoConfiguration.class))
            .withUserConfiguration(DropEmbeddingConfig.class);

    @Test
    @DisplayName("임베딩을 켜면 HTTP 시간 제한 10초·재시도 1회의 Client로 연결하고, 설정한 모델·차원으로 임베딩 모델을 만든다")
    void connection_appliesTimeoutAndNoRetry() {
        runner.withPropertyValues("spring.ai.model.embedding.text=google-genai",
                        "spring.ai.google.genai.embedding.api-key=test-key",
                        "spring.ai.google.genai.embedding.text.options.model=gemini-embedding-001",
                        "spring.ai.google.genai.embedding.text.options.dimensions=768")
                .run(context -> {
                    Client client = context.getBean(GoogleGenAiEmbeddingConnectionDetails.class).getGenAiClient();
                    ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");
                    assertThat(apiClient.httpOptions().timeout()).contains(10_000);
                    assertThat(apiClient.httpOptions().retryOptions().orElseThrow().attempts()).contains(1);

                    GoogleGenAiTextEmbeddingModel model = context.getBean(GoogleGenAiTextEmbeddingModel.class);
                    assertThat(model.options.getModel()).isEqualTo("gemini-embedding-001");
                    assertThat(model.options.getDimensions()).isEqualTo(768);
                });
    }

    @Test
    @DisplayName("임베딩이 꺼져 있으면 모델을 만들지 않고, 켰는데 키가 없으면 기동하지 않는다")
    void embeddingModel_onlyWhenEnabledWithKey() {
        runner.withPropertyValues("spring.ai.model.embedding.text=none")
                .run(context -> assertThat(context).doesNotHaveBean(EmbeddingModel.class));
        runner.withPropertyValues("spring.ai.model.embedding.text=google-genai")
                .run(context -> assertThat(context).hasFailed());
    }
}
