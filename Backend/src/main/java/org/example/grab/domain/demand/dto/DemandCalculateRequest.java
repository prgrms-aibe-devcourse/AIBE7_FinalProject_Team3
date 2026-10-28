package org.example.grab.domain.demand.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

// 모든 필드는 선택이다. 생략하면 기본 가정을 쓴다(DEMAND.md 2.2).
public record DemandCalculateRequest(
        ConversionBasis conversionBasis,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(integer = 1, fraction = 2)
        BigDecimal conversionRate,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2)
        BigDecimal safetyStockRate
) {
    public static DemandCalculateRequest defaults() {
        return new DemandCalculateRequest(null, null, null);
    }

    // 기준 지정과 전환율 직접 입력은 어느 값을 쓸지 모호하므로 함께 받지 않는다
    // 검증 전용이라 요청 스키마의 필드로 보이지 않게 한다
    @JsonIgnore
    @AssertTrue
    public boolean isSingleConversionSource() {
        return conversionBasis == null || conversionRate == null;
    }
}
