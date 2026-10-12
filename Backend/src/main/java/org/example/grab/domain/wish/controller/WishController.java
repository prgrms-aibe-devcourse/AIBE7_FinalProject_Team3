package org.example.grab.domain.wish.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.service.WishService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/drops/{dropId}/wish")
@Tag(name = "WISH", description = "판매 시작 전 DROP의 WISH 등록·취소")
public class WishController {

    private final WishService wishService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "WISH 등록",
            description = "판매 시작 전인 WISH DROP에만 등록한다. 반복 요청해도 활성 WISH는 하나이며 기존 wishedAt을 그대로 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 성공 또는 이미 등록됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "GRAB_ALREADY_STARTED, DROP_NOT_WISHABLE")
    @PutMapping
    public ApiResponse<WishResponse> register(@PathVariable Long dropId) {
        return ApiResponse.success(wishService.register(currentUserIdProvider.currentUserId(), dropId));
    }

    // 명세가 204 No Content이므로 공통 ApiResponse로 감싸지 않고 본문 없이 반환한다.
    @Operation(summary = "WISH 취소", description = "판매 시작 전에만 취소한다. 활성 WISH가 없어도 204로 응답한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "취소 완료")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "GRAB_ALREADY_STARTED, DROP_NOT_WISHABLE")
    @DeleteMapping
    public ResponseEntity<Void> cancel(@PathVariable Long dropId) {
        wishService.cancel(currentUserIdProvider.currentUserId(), dropId);
        return ResponseEntity.noContent().build();
    }
}
