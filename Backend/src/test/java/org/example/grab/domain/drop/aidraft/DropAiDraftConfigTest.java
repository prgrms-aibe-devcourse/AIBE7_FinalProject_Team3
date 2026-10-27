package org.example.grab.domain.drop.aidraft;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import org.example.grab.domain.demand.explanation.DemandExplanationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class DropAiDraftConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
                    GoogleGenAiChatAutoConfiguration.class))
            .withUserConfiguration(DemandExplanationConfig.class, DropAiDraftConfig.class);

    @Test
    @DisplayName("Gemini를 켜면 공용 ChatModel과 별도로 HTTP 시간 제한 19초의 사진 초안 전용 ChatModel을 만들고 기본 옵션을 이어받는다")
    void dropAiDraftChatModel_hasOwnHttpTimeoutAndSharedOptions() {
        runner.withPropertyValues("spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key=test-key",
                        "spring.ai.google.genai.chat.options.model=gemini-2.5-flash-lite",
                        "spring.ai.google.genai.chat.options.temperature=0.2")
                .run(context -> {
                    // 전용 모델이 ChatModel 타입 빈이 아니어야 공용 ChatModel이 그대로 만들어진다
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
                    assertThat(httpTimeout(context.getBean(Client.class))).contains(9_000);

                    GoogleGenAiChatModel draft = context.getBean(DropAiDraftConfig.DraftChatModel.class).chatModel();
                    Client draftClient = (Client) ReflectionTestUtils.getField(draft, "genAiClient");
                    assertThat(httpTimeout(draftClient)).contains(19_000);
                    assertThat(draft.getOptions().getModel()).isEqualTo("gemini-2.5-flash-lite");
                    assertThat(draft.getOptions().getTemperature()).isEqualTo(0.2);
                });
    }

    @Test
    @DisplayName("Gemini가 꺼져 있으면 사진 초안 전용 ChatModel을 만들지 않는다")
    void dropAiDraftChatModel_absentWhenDisabled() {
        runner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertThat(context).doesNotHaveBean(DropAiDraftConfig.DraftChatModel.class));
    }

    @Test
    @DisplayName("HTTP 시간 제한이 서비스 시간 제한보다 길거나 같으면 기동하지 않는다")
    void properties_rejectHttpTimeoutNotShorterThanTimeout() {
        runner.withPropertyValues("spring.ai.model.chat=none",
                        "grab.drop.ai-draft.timeout=20s", "grab.drop.ai-draft.http-timeout=20s")
                .run(context -> assertThat(context).hasFailed());
    }

    private static java.util.Optional<Integer> httpTimeout(Client client) {
        ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");
        return apiClient.httpOptions().timeout();
    }
}
