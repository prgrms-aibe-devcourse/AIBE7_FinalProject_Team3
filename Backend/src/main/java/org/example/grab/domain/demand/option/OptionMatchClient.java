package org.example.grab.domain.demand.option;

/*
    옵션 매칭 LLM 호출. 서비스 테스트에서 실제 모델 없이 응답을 넣으려고 인터페이스로 둔다.
 */
public interface OptionMatchClient {

    // 모델이 꺼져 있으면(spring.ai.model.chat=none) false다
    boolean available();

    Generation generate(String systemPrompt, String input);

    // model은 응답에 실린 실제 모델명이다
    record Generation(String text, String model) {
    }
}
