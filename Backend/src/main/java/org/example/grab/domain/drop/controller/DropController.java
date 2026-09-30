package org.example.grab.domain.drop.controller;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
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
public class DropController {

    private final DropService dropService;

    /*
     * 공개 DROP 목록. page/size와 sort는 서비스 호출 전에 검증해 잘못된 요청이면 서비스를 부르지 않는다.
     * DRAFT·CANCELED는 공개 목록에 넣을 수 없으므로 컨트롤러에서 먼저 거부한다(서비스도 한 번 더 막는다).
     */
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
    @GetMapping("/{dropId}")
    public ApiResponse<PublicDropDetailResponse> getDrop(@PathVariable Long dropId) {
        return ApiResponse.success(dropService.findPublicDrop(dropId));
    }
}
