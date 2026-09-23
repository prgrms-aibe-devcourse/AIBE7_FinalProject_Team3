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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.entity.BaseEntity;
import org.example.grab.global.error.BusinessException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DropImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DropOptionGroup> optionGroups = new ArrayList<>();

    @OneToMany(mappedBy = "drop", cascade = CascadeType.ALL, orphanRemoval = true)
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

    public void replaceImages(List<DropImage> images) {
        ensureEditable();
        this.images.clear();
        this.images.addAll(images);
    }

    public void replaceOptionGroups(List<DropOptionGroup> optionGroups) {
        ensureEditable();
        this.optionGroups.clear();
        this.optionGroups.addAll(optionGroups);
    }

    public void replaceOptions(List<DropOption> options) {
        ensureEditable();
        this.options.clear();
        this.options.addAll(options);
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
