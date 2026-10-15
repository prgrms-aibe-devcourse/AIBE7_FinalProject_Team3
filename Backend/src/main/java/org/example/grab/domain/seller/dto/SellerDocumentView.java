package org.example.grab.domain.seller.dto;

// 워터마크 합성까지 끝난 서류 뷰어 응답(GR-92). JSON이 아니라 이미지 바이너리로 내려간다.
public record SellerDocumentView(byte[] imageBytes, String contentType) {
}
