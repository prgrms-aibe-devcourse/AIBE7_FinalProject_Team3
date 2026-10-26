package org.example.grab.domain.demand.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.grab.domain.demand.entity.DemandPlanStatus;

import java.util.List;

// 요청 계약은 DEMAND.md 2.6을 따른다. 계획의 옵션과 맞는지는 저장된 스냅샷을 아는 서비스가 확인한다.
public record DemandPlanDecisionRequest(
        @NotNull DemandPlanStatus status,
        List<@Valid @NotNull DecidedQuantity> quantities,
        @Size(max = 500) String reason
) {

    public record DecidedQuantity(@NotNull Long optionId, @NotNull @Min(0) Integer quantity) {
    }

    // DRAFT는 결정이 아니므로 받지 않는다
    @JsonIgnore
    @AssertTrue
    public boolean isDecisionStatus() {
        return status != DemandPlanStatus.DRAFT;
    }

    // 판매자가 정한 수량은 MODIFIED에만 보낸다
    @JsonIgnore
    @AssertTrue
    public boolean isQuantitiesOnlyForModified() {
        return (status == DemandPlanStatus.MODIFIED) == (quantities != null && !quantities.isEmpty());
    }
}
