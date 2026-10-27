package org.example.grab.domain.demand.dto;

import org.example.grab.domain.demand.dto.StockRecommendationResponse.SampleSelections;

import java.util.List;

// 응답 계약은 DEMAND.md 7.2를 따른다. 다른 판매자의 옵션 글자·판매량은 담지 않고 정수 비율만 담는다
public record OptionAllocationResponse(
        ConversionBasis basis,
        SampleSelections sampleSelection,
        Integer recommended,
        MatchStatus matchStatus,
        List<GroupAllocation> groups,
        List<OptionAllocation> options
) {

    // 기준이 없어 매칭하지 않았으면 null이다
    public enum MatchStatus {
        TEXT_ONLY,
        GENERATED,
        REUSED,
        DISCARDED,
        FAILED,
        DISABLED
    }

    public enum Allocation {
        SALES,
        EVEN
    }

    // sharePercent는 SALES일 때만 값이 있고 그룹 합이 100이다
    public record GroupAllocation(String name, Allocation allocation, List<ValueShare> values) {
    }

    public record ValueShare(String value, Integer sharePercent) {
    }

    // index는 요청 SKU 순서다
    public record OptionAllocation(int index, Integer recommendedQuantity) {
    }
}
