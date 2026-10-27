package org.example.grab.domain.demand.explanation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringAiDemandExplanationClientTest {

    @Test
    @DisplayName("설정한 모델·온도를 유지한 채 JSON 형식·출력 토큰·thinking 예산을 더해 요청한다")
    void generate_keepsConfiguredModelAndTemperature() {
        // given: spring.ai.google.genai.chat.options로 설정한 기본 옵션
        ChatModel chatModel = mock(ChatModel.class);
        given(chatModel.getOptions()).willReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-2.5-flash-lite").temperature(0.2).build());
        given(chatModel.call(any(Prompt.class))).willReturn(mock(ChatResponse.class, RETURNS_DEEP_STUBS));
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        given(provider.getObject()).willReturn(chatModel);
        DemandExplanationProperties properties =
                new DemandExplanationProperties(Duration.ofSeconds(10), Duration.ofSeconds(9), 1024, 0, 30);

        // when
        new SpringAiDemandExplanationClient(provider, properties).generate("system", "{}");

        // then
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getMaxOutputTokens()).isEqualTo(1024);
        assertThat(options.getThinkingBudget()).isEqualTo(0);
    }
}
