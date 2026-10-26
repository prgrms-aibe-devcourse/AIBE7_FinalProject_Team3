package org.example.grab.domain.demand.dto;

// 표본 DROP들의 합계. wishers·converted·quantity는 DEMAND.md 1.4 정의를 따른다.
public record ConversionSample(int dropCount, long wishers, long converted, long quantity) {

    public static ConversionSample empty() {
        return new ConversionSample(0, 0, 0, 0);
    }
}
