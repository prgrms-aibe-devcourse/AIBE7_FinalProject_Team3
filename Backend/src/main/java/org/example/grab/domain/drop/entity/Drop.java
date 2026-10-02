package org.example.grab.domain.drop.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.validation.DropPublishValidator;
import org.example.grab.global.entity.BaseEntity;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.ErrorCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "drops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Drop extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DropStatus status;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "shipping_fee")
    private Long shippingFee;

    @Column(name = "shipping_notice", columnDefinition = "text")
    private String shippingNotice;

    @Column(name = "sale_starts_at")
    private OffsetDateTime saleStartsAt;

    @Column(name = "sale_ends_at")
    private OffsetDateTime saleEndsAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "grab_started_at")
    private OffsetDateTime grabStartedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private DropCloseReason closeReason;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<DropImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<DropOptionGroup> optionGroups = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<DropOption> options = new ArrayList<>();

    private Drop(Long sellerId) {
        this.sellerId = sellerId;
        this.status = DropStatus.DRAFT;
    }

    public static Drop createDraft(Long sellerId) {
        return new Drop(sellerId);
    }

    public void addImage(DropImage image) {
        images.add(image);
    }

    public void addOptionGroup(DropOptionGroup optionGroup) {
        optionGroups.add(optionGroup);
    }

    public void addOption(DropOption option) {
        options.add(option);
    }

    public void updateDraft(String name, String description, Long categoryId, Long shippingFee,
                            String shippingNotice, OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        ensureEditable();
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (categoryId != null) {
            this.categoryId = categoryId;
        }
        if (shippingFee != null) {
            this.shippingFee = shippingFee;
        }
        if (shippingNotice != null) {
            this.shippingNotice = shippingNotice;
        }
        if (saleStartsAt != null) {
            this.saleStartsAt = saleStartsAt;
        }
        if (saleEndsAt != null) {
            this.saleEndsAt = saleEndsAt;
        }
        validateSchedule();
    }

    public void validateOwner(Long sellerId) {
        if (!this.sellerId.equals(sellerId)) {
            throw new BusinessException(DropErrorCode.DROP_ACCESS_DENIED);
        }
    }

    public void clearImages() {
        ensureEditable();
        images.clear();
    }

    public void clearOptionGroups() {
        ensureEditable();
        optionGroups.clear();
    }

    public void clearOptions() {
        ensureEditable();
        options.clear();
    }

    /**
     * DRAFT를 WISH로 공개한다. 필수 항목 → 일정 → 옵션 구성 순서로 검증한다.
     * 필수 항목 누락은 한 번에 모두 알려, 판매자가 항목마다 재시도하지 않게 한다.
     */
    public void publish(OffsetDateTime now) {
        if (status != DropStatus.DRAFT) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        DropPublishValidator.validateRequired(this);
        validateSchedule();
        DropPublishValidator.validateOptions(this);
        this.status = DropStatus.WISH;
        this.publishedAt = now;
    }

    /**
     * 판매자가 공개한 WISH를 취소한다(GR-18). 취소는 판매 시작 전 WISH에서만 가능하다.
     * 저장 상태가 아직 WISH여도 판매 시작 시각이 지났으면 취소할 수 없다.
     * now는 반드시 DROP 행 잠금(findByIdForUpdate)을 획득한 뒤 생성한 값을 넘겨야 한다.
     */
    public void cancel(String reason, OffsetDateTime now) {
        if (status != DropStatus.WISH || (saleStartsAt != null && !now.isBefore(saleStartsAt))) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = DropStatus.CANCELED;
        this.closedAt = now;
        this.closeReason = DropCloseReason.SELLER_CANCELED;
        this.cancelReason = reason;
    }

    /**
     * WISH 등록·취소 가능 여부를 판정한다. 등록과 취소의 규칙이 같다.
     * 공개되지 않았거나 존재 자체를 숨겨야 하는 DRAFT는 DROP_NOT_FOUND로 응답한다(공개 상세 조회와 동일).
     * WISH라도 판매 시작 시각이 지났으면 GRAB이 시작된 것으로 본다(GR-18 전환 배치 이전의 경합 대비).
     */
    public void validateWishable(OffsetDateTime now) {
        ErrorCode error = wishabilityError(now);
        if (error != null) {
            throw new BusinessException(error);
        }
    }

    /**
     * 공개 상세의 actions.wishable·wishCancelable 판정. validateWishable과 같은 규칙을 공유한다.
     * 비로그인 API이므로 사용자와 무관하게 DROP 상태·시각으로만 정한다.
     */
    public boolean isWishable(OffsetDateTime now) {
        return wishabilityError(now) == null;
    }

    public boolean isPublic() {
        return status != DropStatus.DRAFT;
    }

    // WISH 가능 판정 규칙을 한 곳에 둔다. null이면 가능, 값이 있으면 그 오류로 거부한다.
    private ErrorCode wishabilityError(OffsetDateTime now) {
        return switch (status) {
            // 공개 상세의 isPublic()과 같이 DRAFT의 존재를 숨긴다.
            case DRAFT -> DropErrorCode.DROP_NOT_FOUND;
            case WISH -> saleStartsAt != null && !now.isBefore(saleStartsAt)
                    ? DropErrorCode.GRAB_ALREADY_STARTED : null;
            case GRAB, ENDED -> DropErrorCode.GRAB_ALREADY_STARTED;
            case CANCELED -> DropErrorCode.DROP_NOT_WISHABLE;
        };
    }

    /**
     * 공개 상세의 actions.orderable 판정. 주문 생성 검증(OrderCreateTransactionService.validateSale)과
     * 같은 상태·판매 시각 조건에 품절 여부를 더한다.
     * 저장 상태는 최대 폴링 주기만큼 늦을 수 있으므로 WISH·GRAB을 모두 판매 후보로 보고 서버 시각으로 확정한다.
     */
    public boolean isOrderable(OffsetDateTime now) {
        if (status != DropStatus.WISH && status != DropStatus.GRAB) {
            return false;
        }
        if (saleStartsAt != null && now.isBefore(saleStartsAt)) {
            return false;
        }
        if (saleEndsAt != null && !now.isBefore(saleEndsAt)) {
            return false;
        }
        return !isSoldOut();
    }

    /**
     * 활성 SKU의 가용 재고 합이 0이면 품절로 본다. 활성 SKU가 하나도 없어도 품절이다.
     * 공개 목록 soldOut과 같은 규칙(STOCK-006).
     */
    public boolean isSoldOut() {
        return options.stream()
                .filter(DropOption::isActive)
                .mapToInt(DropOption::getAvailableQuantity)
                .sum() == 0;
    }

    /**
     * 활성 SKU 중 최저가. 활성 SKU가 없으면 null이다.
     * 판매자·공개 상세 응답이 공유하는 규칙이라 DTO가 아니라 도메인에 둔다(공개 목록은 SQL 서브쿼리로 계산).
     */
    public Long getMinPrice() {
        return options.stream()
                .filter(DropOption::isActive)
                .map(DropOption::getUnitPrice)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private void ensureEditable() {
        if (status != DropStatus.DRAFT) {
            throw new BusinessException(DropErrorCode.DROP_NOT_EDITABLE);
        }
    }

    private void validateSchedule() {
        if (saleStartsAt != null && saleEndsAt != null && !saleStartsAt.isBefore(saleEndsAt)) {
            throw new BusinessException(DropErrorCode.INVALID_SCHEDULE);
        }
    }
}
