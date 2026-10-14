package org.example.grab.domain.seller.dto;

import org.example.grab.domain.seller.entity.DocumentType;

import java.util.UUID;

public record SellerDocumentUploadResponse(UUID documentId, DocumentType documentType) {
}
