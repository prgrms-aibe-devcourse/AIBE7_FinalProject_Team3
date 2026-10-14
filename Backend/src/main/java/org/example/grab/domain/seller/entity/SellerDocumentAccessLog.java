package org.example.grab.domain.seller.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

// 운영자가 서류 전용 뷰어로 열람할 때마다 한 행을 남긴다(GR-92, ERD seller_document_access_logs).
@Getter
@Entity
@Table(name = "seller_document_access_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerDocumentAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(name = "accessed_at", nullable = false)
    private OffsetDateTime accessedAt;

    private SellerDocumentAccessLog(Long adminId, Long sellerId, DocumentType documentType, OffsetDateTime accessedAt) {
        this.adminId = Objects.requireNonNull(adminId);
        this.sellerId = Objects.requireNonNull(sellerId);
        this.documentType = Objects.requireNonNull(documentType);
        this.accessedAt = Objects.requireNonNull(accessedAt);
    }

    public static SellerDocumentAccessLog record(Long adminId, Long sellerId, DocumentType documentType,
                                                  OffsetDateTime accessedAt) {
        return new SellerDocumentAccessLog(adminId, sellerId, documentType, accessedAt);
    }
}
