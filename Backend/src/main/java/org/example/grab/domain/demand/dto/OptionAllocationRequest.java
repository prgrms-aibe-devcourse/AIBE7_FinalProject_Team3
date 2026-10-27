package org.example.grab.domain.demand.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;

import java.math.BigDecimal;
import java.util.List;

/*
    옵션별 재고 배분 요청(DEMAND.md 7.2). 재고 추천 요청(5.5)과 같고, 비슷한 상품으로 옵션을 맞춰 보도록
    상품명·설명·옵션 구성이 필수이며 SKU마다 옵션 구성 순서대로 값(values)을 받는다.
 */
public record OptionAllocationRequest(
        @NotNull Long categoryId,
        @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull SkuInput> options,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2) BigDecimal safetyStockRate,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 5000) String description,
        @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull OptionGroupInput> optionGroups
) {

    // 재고 추천 요청의 OptionInput과 OpenAPI 스키마 이름이 겹치지 않도록 다른 이름을 쓴다
    public record SkuInput(
            @NotNull @Min(0) Long unitPrice,
            @Min(0) Integer quantity,
            @NotNull @Size(min = 1, max = 10) List<@NotNull @Size(max = 100) String> values
    ) {
    }

    public StockRecommendationRequest toRecommendationRequest() {
        return new StockRecommendationRequest(
                categoryId,
                options.stream()
                        .map(option -> new StockRecommendationRequest.OptionInput(option.unitPrice(), option.quantity()))
                        .toList(),
                safetyStockRate,
                name,
                description,
                optionGroups);
    }
}
