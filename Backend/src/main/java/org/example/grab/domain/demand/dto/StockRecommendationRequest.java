package org.example.grab.domain.demand.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

// 생성 폼 입력값으로 재고를 추천한다(DEMAND.md 5.5). 저장 전 DROP도 쓰도록 DROP ID는 받지 않는다
public record StockRecommendationRequest(
        @NotNull Long categoryId,
        @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull OptionInput> options,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2) BigDecimal safetyStockRate
) {

    // quantity는 판매자가 입력한 준비 수량이며 비어 있을 수 있다. 모두 1 이상이면 배분 비율과 대표 가격 가중치로 쓴다
    public record OptionInput(@NotNull @Min(0) Long unitPrice, @Min(0) Integer quantity) {
    }
}
