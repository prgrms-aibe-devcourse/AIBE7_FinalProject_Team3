package org.example.grab.domain.demand.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/*
    생성 단계 비슷한 DROP 실적 요청(DEMAND.md 7.5). 저장 전 DROP도 쓰도록 DROP ID는 받지 않는다.
    상품명·설명이 모두 있어야 비슷한 DROP을 고르고, 없으면 내 전체·카테고리 전체로 넓힌다.
 */
public record SimilarSalesRequest(
        @NotNull Long categoryId,
        @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @Size(max = 10) List<@Valid @NotNull OptionGroupInput> optionGroups,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Min(0) Long> unitPrices
) {

    // 비슷한 DROP을 고르는 임베딩 입력에만 쓴다(6.1)
    public record OptionGroupInput(
            @NotNull @Size(max = 100) String name,
            @NotNull @Size(max = 50) List<@NotNull @Size(max = 100) String> values
    ) {
    }
}
