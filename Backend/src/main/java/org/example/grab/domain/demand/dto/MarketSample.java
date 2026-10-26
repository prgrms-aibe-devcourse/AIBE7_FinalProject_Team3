package org.example.grab.domain.demand.dto;

// 다른 판매자 표본 DROP들의 합계. 응답에는 그대로 내보내지 않고 보호 규칙(DEMAND.md 1.3)을 거친 값만 쓴다.
public record MarketSample(int dropCount, int sellerCount, long wishers, long converted, long quantity) {

    public static MarketSample empty() {
        return new MarketSample(0, 0, 0, 0, 0);
    }
}
