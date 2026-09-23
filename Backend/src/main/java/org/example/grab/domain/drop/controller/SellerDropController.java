package org.example.grab.domain.drop.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.response.DropDraftResponse;
import org.example.grab.domain.drop.dto.response.DropPublishResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.CurrentSellerIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/drops")
@RequiredArgsConstructor
public class SellerDropController {

    private final DropService dropService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<DropDraftResponse>> createDraft(
            @Valid @RequestBody DropDraftRequest request) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.createDraft(sellerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(DropDraftResponse.from(drop)));
    }

    @PatchMapping("/{dropId}")
    public ApiResponse<DropDraftResponse> updateDraft(
            @PathVariable Long dropId,
            @Valid @RequestBody DropDraftRequest request) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.updateDraft(sellerId, dropId, request);
        return ApiResponse.success(DropDraftResponse.from(drop));
    }

    @PostMapping("/{dropId}/publish")
    public ApiResponse<DropPublishResponse> publish(@PathVariable Long dropId) {
        Long sellerId = currentSellerIdProvider.currentSellerId();
        Drop drop = dropService.publish(sellerId, dropId);
        return ApiResponse.success(DropPublishResponse.from(drop));
    }
}
