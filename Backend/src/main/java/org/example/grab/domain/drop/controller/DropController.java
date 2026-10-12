package org.example.grab.domain.drop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drops")
@RequiredArgsConstructor
@Tag(name = "공개 DROP", description = "로그인 없이 호출하는 DROP 목록·상세·재고 조회")
public class DropController {

    private final DropService dropService;

    /*
     * 공개 DROP 목록. page/size와 sort는 서비스 호출 전에 검증해 잘못된 요청이면 서비스를 부르지 않는다.
     * DRAFT·CANCELED는 공개 목록에 넣을 수 없으므로 컨트롤러에서 먼저 거부한다(서비스도 한 번 더 막는다).
     */
    @Operation(summary = "공개 DROP 목록 조회",
            description = "WISH·GRAB·ENDED DROP을 상태·카테고리·상품명·품절 여부로 거른다. 정렬·조합 규칙은 DROP.md 1.2 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @GetMapping
    public ApiResponse<PageResponse<PublicDropListResponse>> listDrops(
            @RequestParam(required = false) DropStatus status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean soldOut,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        if (status == DropStatus.DRAFT || status == DropStatus.CANCELED) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        PublicDropSort publicSort = PublicDropSort.parse(sort);
        return ApiResponse.success(dropService.findPublicDrops(
                status, categoryId, keyword, soldOut, publicSort, page, size));
    }

    // 공개 DROP 상세. 비로그인 조회이며 DRAFT는 서비스가 DROP_NOT_FOUND로 숨긴다.
    @Operation(summary = "공개 DROP 상세 조회", description = "옵션, 판매 일정, 배송 정보를 함께 반환한다. DRAFT는 없는 DROP으로 처리한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/{dropId}")
    public ApiResponse<PublicDropDetailResponse> getDrop(@PathVariable Long dropId) {
        return ApiResponse.success(dropService.findPublicDrop(dropId));
    }

    // 공개 재고 재조회. 비로그인 조회이며 DRAFT는 서비스가 DROP_NOT_FOUND로 숨긴다.
    @Operation(summary = "옵션별 재고 재조회",
            description = "활성 옵션의 현재 가용 재고를 반환한다. 표시용이며 실제 구매 가능 여부는 주문 API가 다시 판단한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @GetMapping("/{dropId}/stocks")
    public ApiResponse<PublicDropStockResponse> getStocks(@PathVariable Long dropId) {
        return ApiResponse.success(dropService.findPublicStocks(dropId));
    }
}
