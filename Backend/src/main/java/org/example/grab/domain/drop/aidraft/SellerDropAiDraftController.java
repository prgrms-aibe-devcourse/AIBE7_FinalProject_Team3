package org.example.grab.domain.drop.aidraft;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/drop-drafts")
@RequiredArgsConstructor
@Tag(name = "판매자 DROP AI 초안", description = "업로드한 상품 사진으로 DROP 작성 초안을 만든다. 저장하지 않는다. 403 ACCESS_DENIED는 SELLER 권한 없음")
public class SellerDropAiDraftController {

    private final DropAiDraftService dropAiDraftService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "사진 기반 DROP 초안(AI)",
            description = "업로드한 이미지(최대 3장)로 상품명·설명·카테고리·이미지 대체 설명 초안을 만든다. 저장하지 않으며 항목별로 검증에 실패하면 null이다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초안 생성 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "AI_DRAFT_LIMIT_EXCEEDED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "AI_DRAFT_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_DRAFT_UNAVAILABLE")
    @PostMapping
    public ApiResponse<DropAiDraftResponse> draft(@Valid @RequestBody DropAiDraftRequest request) {
        return ApiResponse.success(dropAiDraftService.draft(currentSellerIdProvider.currentSellerId(), request));
    }
}
