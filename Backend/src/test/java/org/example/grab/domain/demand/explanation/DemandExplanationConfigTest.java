package org.example.grab.domain.demand.explanation;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class DemandExplanationConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DemandExplanationConfig.class);

    @Test
    @DisplayName("Gemini를 켜면 HTTP 시간 제한(기본 9초)을 건 Client를 등록한다")
    void googleGenAiClient_appliesHttpTimeout() {
        runner.withPropertyValues("spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key=test-key")
                .run(context -> {
                    Client client = context.getBean(Client.class);
                    ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");
                    assertThat(apiClient.httpOptions().timeout()).contains(9_000);
                });
    }

    @Test
    @DisplayName("Gemini가 꺼져 있으면 Client를 만들지 않고, 켰는데 키가 없으면 기동하지 않는다")
    void googleGenAiClient_onlyWhenEnabledWithKey() {
        runner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertThat(context).doesNotHaveBean(Client.class));
        runner.withPropertyValues("spring.ai.model.chat=google-genai")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("HTTP 시간 제한이 서비스 시간 제한보다 길거나 같으면 기동하지 않는다")
    void properties_rejectHttpTimeoutNotShorterThanTimeout() {
        runner.withPropertyValues("grab.demand.explanation.timeout=10s", "grab.demand.explanation.http-timeout=10s")
                .run(context -> assertThat(context).hasFailed());
    }
}
