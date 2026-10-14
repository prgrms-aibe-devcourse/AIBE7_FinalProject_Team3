package org.example.grab.domain.seller.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.dto.AdminSellerApplicationResponse;
import org.example.grab.domain.seller.dto.SellerApplicationApprovalResponse;
import org.example.grab.domain.seller.dto.SellerApplicationRejectRequest;
import org.example.grab.domain.seller.dto.SellerApplicationRejectionResponse;
import org.example.grab.domain.seller.dto.SellerDocumentView;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.domain.seller.service.AdminSellerApplicationService;
import org.example.grab.domain.seller.service.SellerDocumentViewService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/seller-applications")
@Tag(name = "판매자 신청 심사", description = "운영자의 판매자 신청 목록 조회·승인·반려(ADMIN 전용)")
public class AdminSellerApplicationController {

    private final AdminSellerApplicationService adminSellerApplicationService;
    private final SellerDocumentViewService sellerDocumentViewService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "판매자 신청 목록 조회", description = "status 미지정 시 전체 상태를 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @GetMapping
    public ApiResponse<PageResponse<AdminSellerApplicationResponse>> findApplications(
            @RequestParam(required = false) SellerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(adminSellerApplicationService.findApplications(status, page, size));
    }

    @Operation(summary = "판매자 신청 승인", description = "PENDING이 아니면 409 INVALID_STATE_TRANSITION")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SELLER_APPLICATION_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @PostMapping("/{applicationId}/approve")
    public ApiResponse<SellerApplicationApprovalResponse> approve(@PathVariable UUID applicationId) {
        return ApiResponse.success(
                adminSellerApplicationService.approve(applicationId, currentUserIdProvider.currentUserId()));
    }

    @Operation(summary = "판매자 신청 반려", description = "사유(reason) 필수. PENDING이 아니면 409 INVALID_STATE_TRANSITION")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반려 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SELLER_APPLICATION_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @PostMapping("/{applicationId}/reject")
    public ApiResponse<SellerApplicationRejectionResponse> reject(@PathVariable UUID applicationId,
            @Valid @RequestBody SellerApplicationRejectRequest request) {
        return ApiResponse.success(adminSellerApplicationService.reject(
                applicationId, currentUserIdProvider.currentUserId(), request.reason()));
    }

    @Operation(summary = "판매자 신청 서류 열람(전용 뷰어)",
            description = "응답은 URL이 아니라 복호화+워터마크 합성을 마친 이미지 바이트다. 열람마다 접근 로그를 남긴다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "열람 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SELLER_APPLICATION_NOT_FOUND")
    @GetMapping("/{applicationId}/documents/{documentType}/view")
    public ResponseEntity<byte[]> viewDocument(@PathVariable UUID applicationId,
            @PathVariable DocumentType documentType) {
        SellerDocumentView view = sellerDocumentViewService.view(
                applicationId, documentType, currentUserIdProvider.currentUserId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(view.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(view.imageBytes());
    }
}
