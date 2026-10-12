package org.example.grab.domain.wish.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.wish.dto.response.WishListResponse;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/wishes")
@Tag(name = "내 WISH", description = "로그인한 사용자의 WISH 목록 조회")
public class UserWishController {

    private final WishQueryService wishQueryService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 사용자 ID는 경로·쿼리로 받지 않고 토큰의 본인 ID를 쓴다. 페이지 검증은 기존 목록과 같은 규칙이다.
    @Operation(summary = "내 WISH 목록 조회", description = "본인이 등록한 활성 WISH를 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @GetMapping
    public ApiResponse<PageResponse<WishListResponse>> listMyWishes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(
                wishQueryService.findMyWishes(currentUserIdProvider.currentUserId(), page, size));
    }
}
