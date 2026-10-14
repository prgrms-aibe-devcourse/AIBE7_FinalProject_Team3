package org.example.grab.domain.seller.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.dto.SellerDocumentUploadResponse;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.service.SellerDocumentUploadService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/seller-documents")
@Tag(name = "판매자 서류", description = "판매자 신청 서류 업로드(암호화 저장)")
public class SellerDocumentController {

    private final SellerDocumentUploadService sellerDocumentUploadService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "판매자 신청 서류 업로드",
            description = "서류 1건을 암호화해 비공개 버킷에 저장하고 documentId를 발급한다. 공개 imageUrl은 내려주지 않는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "업로드 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SellerDocumentUploadResponse>> upload(
            @RequestParam DocumentType documentType,
            @RequestPart("file") MultipartFile file
    ) {
        SellerDocumentUploadResponse response = sellerDocumentUploadService.upload(
                currentUserIdProvider.currentUserId(), documentType, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
