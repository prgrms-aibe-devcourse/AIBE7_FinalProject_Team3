package org.example.grab.domain.demand.explanation;

/*
    설명을 생성하는 LLM 호출. 제공자를 설정으로 바꿀 수 있게 인터페이스 뒤에 둔다(TECHSTACK.md 1.3).
    모델이 설정되지 않았으면 available()이 false이고, 설명 요청은 LLM을 부르지 않고 DISABLED로 응답한다.
 */
public interface DemandExplanationClient {

    boolean available();

    Generation generate(String systemPrompt, String input);

    // model은 응답에 실린 실제 모델명, tokens는 입력·출력 합계(제공자가 알려 주지 않으면 null)
    record Generation(String text, String model, Integer tokens) {
    }
}
