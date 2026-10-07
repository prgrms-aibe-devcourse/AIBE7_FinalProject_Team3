package org.example.grab.domain.wish.controller;

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
public class UserWishController {

    private final WishQueryService wishQueryService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 사용자 ID는 경로·쿼리로 받지 않고 토큰의 본인 ID를 쓴다. 페이지 검증은 기존 목록과 같은 규칙이다.
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
