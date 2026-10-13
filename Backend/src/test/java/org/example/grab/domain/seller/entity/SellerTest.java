package org.example.grab.domain.seller.entity;

import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerTest {

    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    private static final String BRN = "1234567890";

    @Test
    @DisplayName("판매자 신청은 PENDING이고 심사 정보가 비어 있다")
    void applyCreatesPendingSeller() {
        // when
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", BRN, "소개", SUBMITTED_AT);

        // then
        assertThat(seller.getId()).isNull();
        assertThat(seller.getUuid()).isNotNull();
        assertThat(seller.getUserId()).isEqualTo(1L);
        assertThat(seller.getBrandName()).isEqualTo("브랜드");
        assertThat(seller.getContactEmail()).isEqualTo("contact@example.com");
        assertThat(seller.getBusinessRegistrationNumber()).isEqualTo(BRN);
        assertThat(seller.getDescription()).isEqualTo("소개");
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(seller.getReviewedBy()).isNull();
        assertThat(seller.getReviewedAt()).isNull();
        assertThat(seller.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("브랜드 소개는 없어도 신청할 수 있다")
    void applyAllowsMissingDescription() {
        // when
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);

        // then
        assertThat(seller.getDescription()).isNull();
    }

    @Test
    @DisplayName("필수 값이 없으면 신청할 수 없다")
    void applyRejectsMissingRequiredValues() {
        assertThatThrownBy(() -> Seller.apply(null, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, null, "contact@example.com", BRN, null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, "브랜드", null, BRN, null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, "브랜드", "contact@example.com", null, null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PENDING 신청은 승인하거나 반려할 수 있다")
    void approveAndRejectFromPending() {
        Seller approved = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        approved.approve(99L, SUBMITTED_AT);
        assertThat(approved.getStatus()).isEqualTo(SellerStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo(99L);
        assertThat(approved.getReviewedAt()).isEqualTo(SUBMITTED_AT);

        Seller rejected = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        rejected.reject(99L, SUBMITTED_AT, "서류 미비");
        assertThat(rejected.getStatus()).isEqualTo(SellerStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("서류 미비");
    }

    @Test
    @DisplayName("PENDING이 아니면 승인·반려할 수 없다")
    void approveAndRejectRequirePending() {
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        seller.approve(99L, SUBMITTED_AT);

        assertThatThrownBy(() -> seller.approve(99L, SUBMITTED_AT))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> seller.reject(99L, SUBMITTED_AT, "사유"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("REJECTED 신청은 재신청하면 같은 행이 PENDING으로 돌아가고 심사 정보가 비워진다")
    void reapplyResetsReview() {
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        seller.reject(99L, SUBMITTED_AT, "서류 미비");

        seller.reapply("새 브랜드", "new@example.com", "0987654321", "재신청", SUBMITTED_AT);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.getBrandName()).isEqualTo("새 브랜드");
        assertThat(seller.getBusinessRegistrationNumber()).isEqualTo("0987654321");
        assertThat(seller.getReviewedBy()).isNull();
        assertThat(seller.getReviewedAt()).isNull();
        assertThat(seller.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("PENDING·APPROVED 상태에서는 재신청할 수 없다")
    void reapplyRequiresRejected() {
        Seller pending = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        assertThatThrownBy(() -> pending.reapply("브랜드", "contact@example.com", BRN, null, SUBMITTED_AT))
                .isInstanceOf(BusinessException.class);

        Seller approved = Seller.apply(1L, "브랜드", "contact@example.com", BRN, null, SUBMITTED_AT);
        approved.approve(99L, SUBMITTED_AT);
        assertThatThrownBy(() -> approved.reapply("브랜드", "contact@example.com", BRN, null, SUBMITTED_AT))
                .isInstanceOf(BusinessException.class);
    }
}
