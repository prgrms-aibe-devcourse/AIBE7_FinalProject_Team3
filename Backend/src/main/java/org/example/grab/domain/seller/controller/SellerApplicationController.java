package org.example.grab.domain.seller.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.dto.MySellerApplicationResponse;
import org.example.grab.domain.seller.dto.SellerApplicationRequest;
import org.example.grab.domain.seller.dto.SellerApplicationResponse;
import org.example.grab.domain.seller.service.SellerApplicationService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/seller-applications")
@Tag(name = "판매자 신청", description = "일반 회원의 판매자 등록 신청·재신청·내 신청 조회")
public class SellerApplicationController {

    private final SellerApplicationService sellerApplicationService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "판매자 등록 신청",
            description = "PENDING·APPROVED 상태면 409, REJECTED면 같은 건을 재신청으로 갱신한다. "
                    + "사업자등록번호는 국세청 상태조회로 1차 검증한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신청 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "SELLER_APPLICATION_ALREADY_EXISTS, DUPLICATE_BUSINESS_REGISTRATION_NUMBER")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "BUSINESS_NOT_ACTIVE")
    @PostMapping
    public ResponseEntity<ApiResponse<SellerApplicationResponse>> apply(@Valid @RequestBody SellerApplicationRequest request) {
        SellerApplicationResponse response = sellerApplicationService.apply(currentUserIdProvider.currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(summary = "내 판매자 신청 조회", description = "본인 신청 1건을 조회한다. 없으면 404")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SELLER_APPLICATION_NOT_FOUND")
    @GetMapping("/me")
    public ApiResponse<MySellerApplicationResponse> findMyApplication() {
        return ApiResponse.success(sellerApplicationService.findMyApplication(currentUserIdProvider.currentUserId()));
    }
}
