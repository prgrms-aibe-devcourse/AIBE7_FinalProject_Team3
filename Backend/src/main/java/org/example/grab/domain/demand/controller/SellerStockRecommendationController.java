package org.example.grab.domain.demand.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.demand.dto.StockExplanationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.explanation.DemandExplanationResponse;
import org.example.grab.domain.demand.explanation.StockExplanationService;
import org.example.grab.domain.demand.service.StockRecommendationService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/stock-recommendations")
@RequiredArgsConstructor
@Tag(name = "판매자 재고 추천", description = "DROP 생성 단계의 재고 추천·가격대 참고. 저장하지 않는다. 403 ACCESS_DENIED는 SELLER 권한 없음")
public class SellerStockRecommendationController {

    private final StockRecommendationService stockRecommendationService;
    private final StockExplanationService stockExplanationService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "재고 추천",
            description = "같은 카테고리·비슷한 가격대 종료 DROP의 판매 실적으로 준비 권장 수량과 옵션별 배분을 계산하고 가격대별 판매율을 함께 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계산 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @PostMapping
    public ApiResponse<StockRecommendationResponse> recommend(@Valid @RequestBody StockRecommendationRequest request) {
        return ApiResponse.success(stockRecommendationService.recommend(
                currentSellerIdProvider.currentSellerId(), request));
    }

    @Operation(summary = "재고 추천 근거 설명",
            description = "같은 폼 입력의 재고 추천과 비슷한 DROP 실적을 근거로 LLM 설명을 만든다. \"근거 보기\"를 누를 때만 부른다. "
                    + "설명이 실패해도 200으로 status(GENERATED·DISCARDED·FAILED·DISABLED)를 알려 준다. 하루 한도는 수요 분석 설명과 함께 센다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설명 결과(status로 성공 여부 구분)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "DEMAND_EXPLANATION_LIMIT_EXCEEDED")
    @PostMapping("/explanation")
    public ApiResponse<DemandExplanationResponse> explainRecommendation(
            @Valid @RequestBody StockExplanationRequest request) {
        return ApiResponse.success(stockExplanationService.explain(currentSellerIdProvider.currentSellerId(), request));
    }
}
