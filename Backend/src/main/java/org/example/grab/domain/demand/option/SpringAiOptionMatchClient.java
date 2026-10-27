package org.example.grab.domain.demand.option;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/*
    Spring AI ChatModel로 옵션 매칭을 요청한다(DEMAND.md 7.4). 설명 생성과 같은 Gemini 연결(HTTP 9초)을 쓴다.
    응답 형식은 JSON과 응답 스키마로 고정하고, 모델·온도는 spring.ai.google.genai.chat.options 기본값을 이어받는다.
 */
@Component
public class SpringAiOptionMatchClient implements OptionMatchClient {

    private static final int MAX_OUTPUT_TOKENS = 2048;

    private final ObjectProvider<ChatModel> chatModelProvider;

    public SpringAiOptionMatchClient(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    @Override
    public Generation generate(String systemPrompt, String input) {
        ChatModel chatModel = chatModelProvider.getObject();
        // 요청 옵션은 기본 옵션과 합쳐지지 않으므로 기본 옵션에서 시작한다(모델·온도 유지)
        GoogleGenAiChatOptions options = ((GoogleGenAiChatOptions) chatModel.getOptions()).mutate()
                .responseMimeType("application/json")
                .responseSchema(OptionMatchPrompt.RESPONSE_SCHEMA)
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .thinkingBudget(0)
                .build();
        ChatResponse response = chatModel.call(
                new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(input)), options));
        return new Generation(response.getResult().getOutput().getText(), response.getMetadata().getModel());
    }
}
