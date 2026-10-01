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
import org.example.grab.global.entity.UUIDEntity;

import java.time.OffsetDateTime;
import java.util.Objects;

/*
    판매자 신청과 프로필을 한 테이블에서 관리한다(ERD sellers).
    다른 도메인 엔티티를 참조하지 않도록 users는 ID로만 가진다.
 */
@Getter
@Entity
@Table(name = "sellers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends UUIDEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // uq_sellers_user: 회원 한 명당 판매자 한 건
    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    private Long userId;

    @Column(name = "brand_name", nullable = false, length = 100)
    private String brandName;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerStatus status;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    // ck_sellers_review: PENDING이면 심사 컬럼 3개가 모두 null, 반려 사유는 REJECTED일 때만 있다
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    private Seller(Long userId, String brandName, String contactEmail,
                   String description, OffsetDateTime submittedAt) {
        this.userId = Objects.requireNonNull(userId);
        this.brandName = Objects.requireNonNull(brandName);
        this.contactEmail = Objects.requireNonNull(contactEmail);
        this.description = description;
        this.status = SellerStatus.PENDING;
        this.submittedAt = Objects.requireNonNull(submittedAt);
    }

    // 판매자 신청(SELLER-001). 신청 직후는 PENDING이고 심사 정보는 비어 있다
    public static Seller apply(Long userId, String brandName, String contactEmail, String description,
                               OffsetDateTime submittedAt) {
        return new Seller(userId, brandName, contactEmail, description, submittedAt);
    }
}
