package org.example.grab.domain.demand.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.service.DemandAnalysisService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/drops/{dropId}/demand-analysis")
@RequiredArgsConstructor
@Tag(name = "판매자 수요 분석", description = "본인 WISH DROP의 수요 데이터와 판매 계획 계산. 403 ACCESS_DENIED는 SELLER 권한 없음, DROP_ACCESS_DENIED는 다른 판매자의 DROP")
public class SellerDemandController {

    private final DemandAnalysisService demandAnalysisService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "수요 분석 조회",
            description = "활성 WISH·일별 추이와 내 실적·시장 평균 전환율로 기본 가정의 판매 계획을 계산한다. 계산 규칙은 DEMAND.md 1절 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEMAND_ANALYSIS_UNAVAILABLE")
    @GetMapping
    public ApiResponse<DemandAnalysisResponse> analyze(@PathVariable long dropId) {
        return ApiResponse.success(demandAnalysisService.analyze(
                currentSellerIdProvider.currentSellerId(), dropId, DemandCalculateRequest.defaults()));
    }

    @Operation(summary = "가정 변경 재계산",
            description = "전환율 기준(OWN·MARKET) 또는 직접 입력한 전환율과 안전 재고율로 다시 계산한다. 저장하지 않는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계산 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEMAND_ANALYSIS_UNAVAILABLE")
    @PostMapping("/calculate")
    public ApiResponse<DemandAnalysisResponse> calculate(
            @PathVariable long dropId,
            @Valid @RequestBody DemandCalculateRequest request) {
        return ApiResponse.success(demandAnalysisService.analyze(
                currentSellerIdProvider.currentSellerId(), dropId, request));
    }
}
