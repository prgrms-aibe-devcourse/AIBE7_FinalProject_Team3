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
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

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

    // uq_sellers_business_registration_number: 하이픈 제거 10자리 숫자(GR-89)
    @Column(name = "business_registration_number", nullable = false, length = 10)
    private String businessRegistrationNumber;

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

    private Seller(Long userId, String brandName, String contactEmail, String businessRegistrationNumber,
                   String description, OffsetDateTime submittedAt) {
        this.userId = Objects.requireNonNull(userId);
        this.brandName = Objects.requireNonNull(brandName);
        this.contactEmail = Objects.requireNonNull(contactEmail);
        this.businessRegistrationNumber = Objects.requireNonNull(businessRegistrationNumber);
        this.description = description;
        this.status = SellerStatus.PENDING;
        this.submittedAt = Objects.requireNonNull(submittedAt);
    }

    // 판매자 신청(SELLER-001). 신청 직후는 PENDING이고 심사 정보는 비어 있다
    public static Seller apply(Long userId, String brandName, String contactEmail,
                               String businessRegistrationNumber, String description,
                               OffsetDateTime submittedAt) {
        return new Seller(userId, brandName, contactEmail, businessRegistrationNumber, description, submittedAt);
    }

    // 운영자 승인(SELLER-002). PENDING에서만 전이할 수 있다
    public void approve(Long reviewerId, OffsetDateTime reviewedAt) {
        if (status != SellerStatus.PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = SellerStatus.APPROVED;
        this.reviewedBy = Objects.requireNonNull(reviewerId);
        this.reviewedAt = Objects.requireNonNull(reviewedAt);
    }

    // 운영자 반려(SELLER-002). PENDING에서만 전이할 수 있고 사유가 필수다
    public void reject(Long reviewerId, OffsetDateTime reviewedAt, String reason) {
        if (status != SellerStatus.PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = SellerStatus.REJECTED;
        this.reviewedBy = Objects.requireNonNull(reviewerId);
        this.reviewedAt = Objects.requireNonNull(reviewedAt);
        this.rejectionReason = Objects.requireNonNull(reason);
    }

    // 반려 후 재신청(SELLER-001). REJECTED에서만 허용하고 같은 행을 PENDING으로 되돌린다
    public void reapply(String brandName, String contactEmail, String businessRegistrationNumber,
                        String description, OffsetDateTime submittedAt) {
        if (status != SellerStatus.REJECTED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.brandName = Objects.requireNonNull(brandName);
        this.contactEmail = Objects.requireNonNull(contactEmail);
        this.businessRegistrationNumber = Objects.requireNonNull(businessRegistrationNumber);
        this.description = description;
        this.status = SellerStatus.PENDING;
        this.submittedAt = Objects.requireNonNull(submittedAt);
        this.reviewedBy = null;
        this.reviewedAt = null;
        this.rejectionReason = null;
    }
}
