package org.example.grab.domain.seller.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.BaseEntity;

import java.util.Objects;
import java.util.UUID;

/*
    업로드 시 발급하는 documentId를 그대로 PK로 쓴다(ERD seller_documents).
    신청 제출 전 먼저 업로드되므로 sellerId는 업로드 시점엔 비어 있고, 신청 생성 시 attachToSeller로 채운다.
 */
@Getter
@Entity
@Table(name = "seller_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerDocument extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "seller_id")
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "encrypted_object_key", nullable = false, length = 500)
    private String encryptedObjectKey;

    @Column(name = "encrypted_dek", nullable = false, length = 500)
    private String encryptedDek;

    private SellerDocument(UUID id, Long uploadedBy, DocumentType documentType, String contentType,
                           String encryptedObjectKey, String encryptedDek) {
        this.id = Objects.requireNonNull(id);
        this.uploadedBy = Objects.requireNonNull(uploadedBy);
        this.documentType = Objects.requireNonNull(documentType);
        this.contentType = Objects.requireNonNull(contentType);
        this.encryptedObjectKey = Objects.requireNonNull(encryptedObjectKey);
        this.encryptedDek = Objects.requireNonNull(encryptedDek);
    }

    public static SellerDocument upload(UUID id, Long uploadedBy, DocumentType documentType, String contentType,
                                        String encryptedObjectKey, String encryptedDek) {
        return new SellerDocument(id, uploadedBy, documentType, contentType, encryptedObjectKey, encryptedDek);
    }

    // 본인이 업로드했고, 아직 신청에 연결되지 않았거나 재신청으로 본인 신청에 다시 붙이는 서류만 허용한다(도용 방지).
    public boolean isUsableBy(Long userId, Long targetSellerId) {
        return uploadedBy.equals(userId) && (sellerId == null || sellerId.equals(targetSellerId));
    }

    // 판매자 신청 제출 시 이 서류가 어떤 신청에 속하는지 기록한다.
    public void attachToSeller(Long sellerId) {
        this.sellerId = Objects.requireNonNull(sellerId);
    }
}
