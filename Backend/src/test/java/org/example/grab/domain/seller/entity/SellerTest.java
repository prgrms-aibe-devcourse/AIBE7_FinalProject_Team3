package org.example.grab.domain.seller.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerTest {

    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("판매자 신청은 PENDING이고 심사 정보가 비어 있다")
    void applyCreatesPendingSeller() {
        // when
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", "소개", SUBMITTED_AT);

        // then
        assertThat(seller.getId()).isNull();
        assertThat(seller.getUuid()).isNotNull();
        assertThat(seller.getUserId()).isEqualTo(1L);
        assertThat(seller.getBrandName()).isEqualTo("브랜드");
        assertThat(seller.getContactEmail()).isEqualTo("contact@example.com");
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
        Seller seller = Seller.apply(1L, "브랜드", "contact@example.com", null, SUBMITTED_AT);

        // then
        assertThat(seller.getDescription()).isNull();
    }

    @Test
    @DisplayName("필수 값이 없으면 신청할 수 없다")
    void applyRejectsMissingRequiredValues() {
        assertThatThrownBy(() -> Seller.apply(null, "브랜드", "contact@example.com", null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, null, "contact@example.com", null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, "브랜드", null, null, SUBMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Seller.apply(1L, "브랜드", "contact@example.com", null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
