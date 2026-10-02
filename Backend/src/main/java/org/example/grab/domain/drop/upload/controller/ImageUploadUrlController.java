package org.example.grab.domain.drop.upload.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlRequest;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.domain.drop.upload.service.DropImageUploadService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads/images")
@RequiredArgsConstructor
public class ImageUploadUrlController {

    private final DropImageUploadService dropImageUploadService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @PostMapping("/presigned-url")
    public ApiResponse<ImageUploadUrlResponse> issuePresignedUrl(
            @Valid @RequestBody ImageUploadUrlRequest request) {
        // URL 권한은 SecurityConfig가, 승인 상태는 currentSellerIdProvider가 DB로 다시 확인한다(GR-64와 같은 이중 확인).
        // 승인 취소 판매자를 거르는 것이 목적이라 반환값은 쓰지 않는다.
        currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropImageUploadService.issue(request.contentType(), request.fileSize()));
    }
}
