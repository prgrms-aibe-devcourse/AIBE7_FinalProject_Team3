package org.example.grab.domain.demand.embedding;

/*
    DROP 임베딩을 만드는 모델 호출. 서비스 테스트에서 실제 API 없이 결과를 넣으려고 인터페이스로 둔다.
 */
public interface DropEmbeddingClient {

    // 임베딩 모델이 꺼져 있으면(spring.ai.model.embedding.text=none) false다
    boolean available();

    // 저장할 모델명(설정값). 바뀌면 배치가 모든 DROP을 다시 만든다
    String model();

    float[] embed(String text);
}
