package org.example.grab.domain.demand.explanation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

// 검증을 통과한 설명. demand_explanations.explanation에 이 형식 그대로 저장하고 응답한다(DEMAND.md 3.1)
public record DemandExplanation(
        String summary,
        List<String> reasons,
        List<String> risks,
        List<String> dataGaps,
        List<String> notRecommended
) {

    @JsonIgnore
    public List<String> texts() {
        List<String> texts = new ArrayList<>();
        texts.add(summary);
        texts.addAll(reasons);
        texts.addAll(risks);
        texts.addAll(dataGaps);
        texts.addAll(notRecommended);
        return texts;
    }
}
