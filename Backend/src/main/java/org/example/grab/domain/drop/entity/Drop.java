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
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.entity.BaseEntity;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.ErrorCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<DropImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<DropOptionGroup> optionGroups = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
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
        validateRequiredForPublish();
        validateSchedule();
        validateOptionsForPublish();
        this.status = DropStatus.WISH;
        this.publishedAt = now;
    }

    // 필드명은 요청 DTO(DropDraftRequest) 기준으로 적어 클라이언트가 입력 위치를 바로 찾을 수 있게 한다.
    private void validateRequiredForPublish() {
        List<ErrorResponse.FieldError> missing = new ArrayList<>();
        if (name == null || name.isBlank()) {
            missing.add(required("name"));
        }
        if (description == null || description.isBlank()) {
            missing.add(required("description"));
        }
        if (categoryId == null) {
            missing.add(required("categoryId"));
        }
        if (shippingFee == null) {
            missing.add(required("shipping.shippingFee"));
        }
        if (shippingNotice == null || shippingNotice.isBlank()) {
            missing.add(required("shipping.shippingNotice"));
        }
        if (saleStartsAt == null) {
            missing.add(required("saleStartsAt"));
        }
        if (saleEndsAt == null) {
            missing.add(required("saleEndsAt"));
        }
        if (images.isEmpty()) {
            missing.add(new ErrorResponse.FieldError("imageUrls", "이미지를 1개 이상 등록해야 합니다."));
        }
        if (options.isEmpty()) {
            missing.add(new ErrorResponse.FieldError("options", "구매 옵션을 1개 이상 등록해야 합니다."));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, missing);
        }
    }

    /**
     * SKU 조합은 엔티티 인스턴스가 아니라 (그룹명 → 값) 맵으로 비교한다.
     * LAZY 프록시와 실제 엔티티가 섞이면 인스턴스 비교가 틀어지고, 그룹명·값은 앞 단계에서 고유함을 확인하므로 키로 충분하다.
     * 옵션 없는 상품은 그룹 0개 + 선택 없는 SKU 1개이며, 같은 규칙으로 빈 조합 하나만 허용된다.
     */
    private void validateOptionsForPublish() {
        Set<String> groupNames = new HashSet<>();
        for (int i = 0; i < optionGroups.size(); i++) {
            DropOptionGroup group = optionGroups.get(i);
            Set<String> values = new HashSet<>();
            boolean duplicateValue = group.getValues().stream()
                    .map(DropOptionValue::getValue)
                    .anyMatch(value -> !values.add(value));
            if (!groupNames.add(group.getName()) || duplicateValue) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        "optionGroups[" + i + "]", "옵션 그룹명 또는 그룹 안의 옵션값이 중복됩니다.");
            }
        }

        Set<Map<String, String>> combinations = new HashSet<>();
        boolean hasSellableOption = false;
        for (int i = 0; i < options.size(); i++) {
            DropOption option = options.get(i);
            String field = "options[" + i + "]";

            Map<String, String> combination = new HashMap<>();
            boolean selectedGroupTwice = false;
            for (DropOptionValueMap valueMap : option.getValueMaps()) {
                selectedGroupTwice |= combination.put(valueMap.getGroup().getName(), valueMap.getValue().getValue()) != null;
            }
            if (selectedGroupTwice || !combination.keySet().equals(groupNames)) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        field, "모든 옵션 그룹에서 값을 정확히 하나씩 선택해야 합니다.");
            }
            if (!combinations.add(combination)) {
                throw optionError(DropErrorCode.DUPLICATE_OPTION_COMBINATION,
                        field, "동일한 옵션값 조합의 SKU가 이미 있습니다.");
            }
            if (option.getUnitPrice() == null || option.getUnitPrice() < 0 || option.getTotalQuantity() < 0) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        field, "가격과 재고는 0 이상이어야 합니다.");
            }
            hasSellableOption |= option.isActive() && option.getAvailableQuantity() >= 1;
        }
        if (!hasSellableOption) {
            throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                    "options", "활성 상태이며 재고가 1개 이상인 SKU가 최소 하나 필요합니다.");
        }
    }

    private static ErrorResponse.FieldError required(String field) {
        return new ErrorResponse.FieldError(field, "공개하려면 필수로 입력해야 합니다.");
    }

    private static BusinessException optionError(ErrorCode errorCode, String field, String reason) {
        return new BusinessException(errorCode, List.of(new ErrorResponse.FieldError(field, reason)));
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
