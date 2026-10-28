package org.example.grab.domain.demand.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.demand.dto.SimilarSalesRequest;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.service.SimilarSalesService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
@Tag(name = "판매자 비슷한 DROP 실적",
        description = "내 지난 비슷한 DROP과 다른 판매자의 비슷한 굿즈의 판매 결과. 저장하지 않는다. 403 ACCESS_DENIED는 SELLER 권한 없음")
public class SellerSimilarSalesController {

    private final SimilarSalesService similarSalesService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "생성 단계 비슷한 DROP 실적",
            description = "폼 입력(카테고리·상품명·설명·옵션·가격)으로 비슷한 종료 DROP의 매진 비율·매진 시간·판매율·WISH 대비 판매량을 계산한다. "
                    + "내 비슷한 DROP이 충분하면 내 것을, 아니면 다른 판매자 것을 기준으로 삼는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계산 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @PostMapping("/similar-sales")
    public ApiResponse<SimilarSalesResponse> forForm(@Valid @RequestBody SimilarSalesRequest request) {
        return ApiResponse.success(similarSalesService.forForm(currentSellerIdProvider.currentSellerId(), request));
    }

    @Operation(summary = "WISH 단계 비슷한 DROP 실적",
            description = "본인 WISH DROP의 저장된 임베딩으로 비슷한 종료 DROP의 판매 결과를 계산한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계산 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEMAND_ANALYSIS_UNAVAILABLE")
    @GetMapping("/drops/{dropId}/similar-sales")
    public ApiResponse<SimilarSalesResponse> forWishDrop(@PathVariable Long dropId) {
        return ApiResponse.success(similarSalesService.forWishDrop(currentSellerIdProvider.currentSellerId(), dropId));
    }
}
