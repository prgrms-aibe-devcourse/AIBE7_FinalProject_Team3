package org.example.grab.domain.drop.aidraft;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.util.List;

/*
    사진 초안 전용 ChatModel(DropAiDraftConfig)에 이미지와 함께 초안을 요청한다. spring.ai.model.chat이 none이면
    전용 ChatModel이 없어 available()이 false다. 응답 형식은 JSON으로 고정하고(responseMimeType),
    모델·온도는 spring.ai.google.genai.chat.options 기본값을 이어받는다.
 */
@Component
public class SpringAiDropAiDraftClient implements DropAiDraftClient {

    private final ObjectProvider<DropAiDraftConfig.DraftChatModel> chatModelProvider;
    private final DropAiDraftProperties properties;

    public SpringAiDropAiDraftClient(
            ObjectProvider<DropAiDraftConfig.DraftChatModel> chatModelProvider,
            DropAiDraftProperties properties
    ) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    @Override
    public Generation generate(String systemPrompt, String instruction, List<DraftImage> images) {
        ChatModel chatModel = chatModelProvider.getObject().chatModel();
        List<Media> media = images.stream()
                .map(image -> Media.builder()
                        .mimeType(MimeType.valueOf(image.contentType()))
                        .data(image.data())
                        .build())
                .toList();
        // 요청에 옵션을 넣으면 기본 옵션과 합치지 않고 그대로 쓴다. 새로 만들면 설정한 모델·온도가 빠지고
        // 빌더 기본값(gemini-2.5-flash·0.7)으로 나가므로 기본 옵션에서 시작한다
        GoogleGenAiChatOptions options = ((GoogleGenAiChatOptions) chatModel.getOptions()).mutate()
                .responseMimeType("application/json")
                .responseSchema(DropAiDraftPrompt.RESPONSE_SCHEMA)
                .maxOutputTokens(properties.maxOutputTokens())
                .thinkingBudget(properties.thinkingBudget())
                .build();
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                UserMessage.builder().text(instruction).media(media).build()), options));
        Usage usage = response.getMetadata().getUsage();
        return new Generation(
                response.getResult().getOutput().getText(),
                response.getMetadata().getModel(),
                usage == null ? null : usage.getTotalTokens());
    }
}
