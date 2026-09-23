package org.example.grab.domain.drop.entity;

import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DropPublishTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-18T07:00:00Z");

    @Test
    @DisplayName("검증을 통과하면 WISH로 전환되고 publishedAt이 기록된다")
    void publish_success() {
        // given
        Drop drop = readyDrop();
        DropOptionGroup color = addGroup(drop, "색상", "블랙", "화이트");
        addOption(drop, 1000L, 5, true, color.getValues().get(0));
        addOption(drop, 1000L, 5, true, color.getValues().get(1));

        // when
        drop.publish(NOW);

        // then
        assertThat(drop.getStatus()).isEqualTo(DropStatus.WISH);
        assertThat(drop.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("옵션 없는 상품은 선택 없는 기본 SKU 한 개로 공개된다")
    void publish_noOptionProduct() {
        // given
        Drop drop = readyDrop();
        addOption(drop, 1000L, 5, true);

        // when
        drop.publish(NOW);

        // then
        assertThat(drop.getStatus()).isEqualTo(DropStatus.WISH);
    }

    @Test
    @DisplayName("DRAFT가 아니면 INVALID_STATE_TRANSITION")
    void publish_rejectsNonDraft() {
        // given
        Drop drop = readyDrop();
        addOption(drop, 1000L, 5, true);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);

        // when & then
        assertThatThrownBy(() -> drop.publish(NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("필수 항목이 비어 있으면 VALIDATION_FAILED와 누락 항목 전부를 fieldErrors로 알린다")
    void publish_rejectsMissingRequiredFields() {
        // given
        Drop drop = Drop.createDraft(1L);

        // when & then
        assertThatThrownBy(() -> drop.publish(NOW))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("name", "description", "categoryId", "shipping.shippingFee",
                                    "shipping.shippingNotice", "saleStartsAt", "saleEndsAt", "imageUrls", "options");
                });
    }

    @Test
    @DisplayName("시작 시각이 종료 시각과 같으면 INVALID_SCHEDULE")
    void publish_rejectsInvalidSchedule() {
        // given
        Drop drop = readyDrop();
        addOption(drop, 1000L, 5, true);
        ReflectionTestUtils.setField(drop, "saleEndsAt", drop.getSaleStartsAt());

        // when & then
        assertThatThrownBy(() -> drop.publish(NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("옵션 그룹명이 중복되면 INVALID_OPTION_COMBINATION")
    void publish_rejectsDuplicateGroupName() {
        // given
        Drop drop = readyDrop();
        DropOptionGroup first = addGroup(drop, "색상", "블랙");
        addGroup(drop, "색상", "화이트");
        addOption(drop, 1000L, 5, true, first.getValues().get(0));

        // when & then
        assertOptionError(drop, DropErrorCode.INVALID_OPTION_COMBINATION, "optionGroups[1]");
    }

    @Test
    @DisplayName("그룹 하나를 선택하지 않은 SKU는 INVALID_OPTION_COMBINATION")
    void publish_rejectsMissingGroupSelection() {
        // given
        Drop drop = readyDrop();
        DropOptionGroup color = addGroup(drop, "색상", "블랙");
        addGroup(drop, "사이즈", "M");
        addOption(drop, 1000L, 5, true, color.getValues().get(0));

        // when & then
        assertOptionError(drop, DropErrorCode.INVALID_OPTION_COMBINATION, "options[0]");
    }

    @Test
    @DisplayName("같은 값 조합의 SKU가 있으면 DUPLICATE_OPTION_COMBINATION")
    void publish_rejectsDuplicateCombination() {
        // given
        Drop drop = readyDrop();
        DropOptionGroup color = addGroup(drop, "색상", "블랙");
        addOption(drop, 1000L, 5, true, color.getValues().get(0));
        addOption(drop, 2000L, 3, true, color.getValues().get(0));

        // when & then
        assertOptionError(drop, DropErrorCode.DUPLICATE_OPTION_COMBINATION, "options[1]");
    }

    @Test
    @DisplayName("옵션 없는 상품에 기본 SKU가 두 개면 DUPLICATE_OPTION_COMBINATION")
    void publish_rejectsTwoDefaultSkus() {
        // given
        Drop drop = readyDrop();
        addOption(drop, 1000L, 5, true);
        addOption(drop, 1000L, 5, true);

        // when & then
        assertOptionError(drop, DropErrorCode.DUPLICATE_OPTION_COMBINATION, "options[1]");
    }

    @Test
    @DisplayName("음수 가격은 INVALID_OPTION_COMBINATION")
    void publish_rejectsNegativePrice() {
        // given
        Drop drop = readyDrop();
        addOption(drop, -1L, 5, true);

        // when & then
        assertOptionError(drop, DropErrorCode.INVALID_OPTION_COMBINATION, "options[0]");
    }

    @Test
    @DisplayName("재고가 0이거나 비활성인 SKU만 있으면 INVALID_OPTION_COMBINATION")
    void publish_rejectsNoSellableOption() {
        // given
        Drop drop = readyDrop();
        DropOptionGroup color = addGroup(drop, "색상", "블랙", "화이트");
        addOption(drop, 1000L, 0, true, color.getValues().get(0));
        addOption(drop, 1000L, 5, false, color.getValues().get(1));

        // when & then
        assertOptionError(drop, DropErrorCode.INVALID_OPTION_COMBINATION, "options");
    }

    private void assertOptionError(Drop drop, DropErrorCode errorCode, String field) {
        assertThatThrownBy(() -> drop.publish(NOW))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(errorCode);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field).containsExactly(field);
                });
        assertThat(drop.getStatus()).isEqualTo(DropStatus.DRAFT);
    }

    // 옵션을 제외한 필수 항목이 모두 채워진 DRAFT.
    private Drop readyDrop() {
        Drop drop = Drop.createDraft(1L);
        drop.updateDraft("상품", "설명", 1L, 3000L, "안내", NOW.plusDays(1), NOW.plusDays(2));
        drop.addImage(DropImage.create(drop, "https://example.com/a.jpg", 0, "상품"));
        return drop;
    }

    private DropOptionGroup addGroup(Drop drop, String name, String... values) {
        DropOptionGroup group = DropOptionGroup.create(drop, name, drop.getOptionGroups().size());
        for (int i = 0; i < values.length; i++) {
            group.addValue(DropOptionValue.create(group, values[i], i));
        }
        drop.addOptionGroup(group);
        return group;
    }

    private void addOption(Drop drop, Long unitPrice, int quantity, boolean active, DropOptionValue... values) {
        DropOption option = DropOption.create(drop, unitPrice, quantity, drop.getOptions().size());
        option.updateActive(active);
        for (DropOptionValue value : values) {
            option.addValueMap(DropOptionValueMap.create(option, value));
        }
        drop.addOption(option);
    }
}
