package org.example.grab.domain.drop.aidraft;

import org.springframework.ai.google.genai.GoogleGenAiChatModel;

/*
    사진 초안 전용 ChatModel. ChatModel 타입 빈으로 등록하면 자동 구성(@ConditionalOnMissingBean)이
    수요 분석 설명용 ChatModel을 만들지 않으므로 이 타입으로 감싸 등록한다(DropAiDraftConfig).
 */
public record DropAiDraftChatModel(GoogleGenAiChatModel chatModel) {
}
