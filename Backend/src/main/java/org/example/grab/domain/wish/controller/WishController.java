package org.example.grab.domain.wish.controller;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.service.WishService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/drops/{dropId}/wish")
public class WishController {

    private final WishService wishService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @PutMapping
    public ApiResponse<WishResponse> register(@PathVariable Long dropId) {
        return ApiResponse.success(wishService.register(currentUserIdProvider.currentUserId(), dropId));
    }

    // 명세가 204 No Content이므로 공통 ApiResponse로 감싸지 않고 본문 없이 반환한다.
    @DeleteMapping
    public ResponseEntity<Void> cancel(@PathVariable Long dropId) {
        wishService.cancel(currentUserIdProvider.currentUserId(), dropId);
        return ResponseEntity.noContent().build();
    }
}
