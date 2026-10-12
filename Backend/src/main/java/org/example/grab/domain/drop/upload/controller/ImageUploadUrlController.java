package org.example.grab.domain.drop.upload.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "이미지 업로드", description = "DROP 상품 이미지용 Supabase Storage 업로드 URL 발급")
public class ImageUploadUrlController {

    private final DropImageUploadService dropImageUploadService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "이미지 업로드 URL 발급",
            description = "SELLER 전용. 받은 uploadUrl로 클라이언트가 직접 PUT 업로드하고, imageId·imageUrl을 DROP 저장 요청에 쓴다. 형식·크기 제한은 IMAGE_UPLOAD.md 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (SELLER 권한 없음·CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_SERVICE_ERROR")
    @PostMapping("/presigned-url")
    public ApiResponse<ImageUploadUrlResponse> issuePresignedUrl(
            @Valid @RequestBody ImageUploadUrlRequest request) {
        // URL 권한은 SecurityConfig가, 승인 상태는 currentSellerIdProvider가 DB로 다시 확인한다(GR-64와 같은 이중 확인).
        // 승인 취소 판매자를 거르는 것이 목적이라 반환값은 쓰지 않는다.
        currentSellerIdProvider.currentSellerId();
        return ApiResponse.success(dropImageUploadService.issue(request.contentType(), request.fileSize()));
    }
}
