package org.example.grab.domain.demand.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
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
}
