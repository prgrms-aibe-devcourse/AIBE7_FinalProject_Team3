package org.example.grab.domain.demand.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
    Spring AI EmbeddingModel로 임베딩을 만든다. 모델·출력 차원은 spring.ai.google.genai.embedding.text.options 기본값을 쓴다.
    Spring AI 2.0.1의 Gemini 임베딩은 작업 유형(taskType)을 요청에 넣지 않는다. 모든 DROP과 비교 대상을 같은 방식으로
    만들므로 유사도 비교에는 영향이 없다(수요 분석 API 6.2).
 */
@Component
public class SpringAiDropEmbeddingClient implements DropEmbeddingClient {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final String model;

    public SpringAiDropEmbeddingClient(
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            @Value("${spring.ai.google.genai.embedding.text.options.model:}") String model
    ) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.model = model;
    }

    @Override
    public boolean available() {
        return embeddingModelProvider.getIfAvailable() != null;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModelProvider.getObject().embed(text);
    }
}
