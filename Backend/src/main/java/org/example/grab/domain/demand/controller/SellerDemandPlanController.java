package org.example.grab.domain.demand.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest;
import org.example.grab.domain.demand.dto.DemandPlanResponse;
import org.example.grab.domain.demand.dto.DemandPlanSummaryResponse;
import org.example.grab.domain.demand.service.DemandPlanService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
@Tag(name = "판매자 판매 계획", description = "수요 분석 판매 계획 저장·이력·결정 기록. 결정은 재고·DROP을 바꾸지 않는다. 403 ACCESS_DENIED는 SELLER 권한 없음")
public class SellerDemandPlanController {

    private final DemandPlanService demandPlanService;
    private final CurrentSellerIdProvider currentSellerIdProvider;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "판매 계획 저장",
            description = "저장 시점 데이터로 다시 계산해 DRAFT로 저장한다. 요청은 가정 변경 재계산과 같다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "저장 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEMAND_ANALYSIS_UNAVAILABLE")
    @PostMapping("/drops/{dropId}/demand-plans")
    public ResponseEntity<ApiResponse<DemandPlanResponse>> save(
            @PathVariable long dropId,
            @Valid @RequestBody DemandCalculateRequest request) {
        DemandPlanResponse response = demandPlanService.save(
                currentSellerIdProvider.currentSellerId(), currentUserIdProvider.currentUserId(), dropId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(summary = "판매 계획 목록", description = "DROP의 판매 계획을 최근 저장순으로 반환한다. DROP 상태와 관계없이 조회한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/drops/{dropId}/demand-plans")
    public ApiResponse<PageResponse<DemandPlanSummaryResponse>> findPlans(
            @PathVariable long dropId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(demandPlanService.findPlans(
                currentSellerIdProvider.currentSellerId(), dropId, page, size));
    }

    @Operation(summary = "판매 계획 상세", description = "저장 시점 수요 분석 스냅샷과 결정·설명 상태를 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DEMAND_PLAN_NOT_FOUND, RESOURCE_NOT_FOUND")
    @GetMapping("/demand-plans/{planId}")
    public ApiResponse<DemandPlanResponse> findPlan(@PathVariable String planId) {
        return ApiResponse.success(demandPlanService.findPlan(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(planId)));
    }

    @Operation(summary = "판매 계획 결정 기록",
            description = "ACCEPTED·MODIFIED·REJECTED 중 하나를 계획당 한 번 기록한다. MODIFIED는 계획의 모든 옵션 수량을 보낸다. DROP 판매 수량은 바꾸지 않는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DEMAND_PLAN_NOT_FOUND, RESOURCE_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEMAND_PLAN_ALREADY_DECIDED, DEMAND_ANALYSIS_UNAVAILABLE")
    @PostMapping("/demand-plans/{planId}/decision")
    public ApiResponse<DemandPlanResponse> decide(
            @PathVariable String planId,
            @Valid @RequestBody DemandPlanDecisionRequest request) {
        return ApiResponse.success(demandPlanService.decide(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(planId), request));
    }
}
