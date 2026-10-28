package org.example.grab.domain.demand.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.grab.domain.demand.dto.SimilarSalesRequest.OptionGroupInput;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;

import java.math.BigDecimal;
import java.util.List;

/*
    생성 단계 추천 근거 설명 요청(DEMAND.md 8.1). 재고 추천(5.5)과 비슷한 DROP 실적(7.5) 요청을 합친 폼 입력이다.
    상품명·설명·옵션 그룹은 비슷한 DROP을 고르는 요청 임베딩에만 쓰고 LLM 입력에는 넣지 않는다(8.2).
 */
public record StockExplanationRequest(
        @NotNull Long categoryId,
        @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @Size(max = 10) List<@Valid @NotNull OptionGroupInput> optionGroups,
        @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull OptionInput> options,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2) BigDecimal safetyStockRate
) {

    public StockRecommendationRequest toStockRecommendationRequest() {
        return new StockRecommendationRequest(categoryId, options, safetyStockRate);
    }

    public SimilarSalesRequest toSimilarSalesRequest() {
        return new SimilarSalesRequest(categoryId, name, description, optionGroups,
                options.stream().map(OptionInput::unitPrice).toList());
    }
}
