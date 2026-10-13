package org.example.grab.domain.drop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.request.DropCancelRequest;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.response.DropCancelResponse;
import org.example.grab.domain.drop.dto.response.DropDraftResponse;
import org.example.grab.domain.drop.dto.response.DropPublishResponse;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.dto.response.SellerDropStockResponse;
import org.example.grab.domain.drop.dto.response.SellerDropWishCountResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/drops")
@RequiredArgsConstructor
@Tag(name = "판매자 DROP", description = "본인 DROP의 임시 저장·수정·공개·취소와 현황 조회. "
        + "403 ACCESS_DENIED는 SELLER 권한 없음 또는 CSRF 토큰 누락, DROP_ACCESS_DENIED는 다른 판매자의 DROP")
public class SellerDropController {

    private final DropService dropService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "DROP 임시 저장",
            description = "DRAFT로 저장하며 일부 항목이 비어 있어도 된다. images는 GALLERY(최대 5개), "
                    + "detailImages는 DETAIL(최대 15개)이다. 이미지·옵션 규칙은 DROP.md 2.1 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "저장 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "INVALID_SCHEDULE, INVALID_OPTION_COMBINATION")
    @PostMapping
    public ResponseEntity<ApiResponse<DropDraftResponse>> createDraft(
            @Valid @RequestBody DropDraftRequest request) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.createDraft(sellerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(DropDraftResponse.from(drop)));
    }

    @Operation(summary = "DRAFT DROP 수정",
            description = "보낸 필드만 바꾼다. DRAFT 상태에서만 수정할 수 있다. images·detailImages는 용도별로 "
                    + "누락/null=유지, []=삭제, 배열=교체다. 자세한 규칙은 DROP.md 2.4 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DROP_NOT_EDITABLE")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "INVALID_SCHEDULE, INVALID_OPTION_COMBINATION")
    @PatchMapping("/{dropId}")
    public ApiResponse<DropDraftResponse> updateDraft(
            @PathVariable Long dropId,
            @Valid @RequestBody DropDraftRequest request) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.updateDraft(sellerId, dropId, request);
        return ApiResponse.success(DropDraftResponse.from(drop));
    }

    @Operation(summary = "DROP 공개",
            description = "DRAFT를 WISH로 바꾼다. 공개 전에 필수 항목(GALLERY 이미지 1개 이상 포함)·일정·옵션을 모두 검증하고 "
                    + "위반 항목은 fieldErrors로 알려준다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공개 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION, DUPLICATE_OPTION_COMBINATION")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "INVALID_SCHEDULE, INVALID_OPTION_COMBINATION")
    @PostMapping("/{dropId}/publish")
    public ApiResponse<DropPublishResponse> publish(@PathVariable Long dropId) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.publish(sellerId, dropId);
        return ApiResponse.success(DropPublishResponse.from(drop));
    }

    @Operation(summary = "WISH DROP 출시 취소", description = "판매 시작 전인 WISH DROP만 취소할 수 있다. 취소 사유가 필요하다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @PostMapping("/{dropId}/cancel")
    public ApiResponse<DropCancelResponse> cancel(
            @PathVariable Long dropId,
            @Valid @RequestBody DropCancelRequest request) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.cancel(sellerId, dropId, request.reason());
        return ApiResponse.success(DropCancelResponse.from(drop));
    }

    @Operation(summary = "판매자 DROP 목록 조회", description = "본인 DROP만 반환한다. 상태로 거를 수 있다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @GetMapping
    public ApiResponse<PageResponse<SellerDropListResponse>> listDrops(
            @RequestParam(required = false) DropStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Long sellerId = currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropService.findSellerDrops(sellerId, status, page, size));
    }

    @Operation(summary = "판매자 DROP 상세 조회", description = "DRAFT를 포함해 본인 DROP의 상세를 반환한다. 이미지는 images(GALLERY)와 detailImages(DETAIL)로 나눈다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/{dropId}")
    public ApiResponse<SellerDropDetailResponse> findDrop(@PathVariable Long dropId) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropService.findSellerDrop(sellerId, dropId));
    }

    @Operation(summary = "DROP 활성 WISH 수 조회", description = "본인 DROP에 등록된 활성 WISH 수를 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/{dropId}/wish-count")
    public ApiResponse<SellerDropWishCountResponse> findWishCount(@PathVariable Long dropId) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropService.findSellerWishCount(sellerId, dropId));
    }

    @Operation(summary = "판매자 재고 현황 조회",
            description = "비활성 옵션을 포함해 옵션별 전체·가용·확보·판매 재고를 반환한다. 상태와 관계없이 조회할 수 있다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, DROP_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/{dropId}/stocks")
    public ApiResponse<SellerDropStockResponse> findStocks(@PathVariable Long dropId) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropService.findSellerStocks(sellerId, dropId));
    }
}
