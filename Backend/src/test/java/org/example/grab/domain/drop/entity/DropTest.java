package org.example.grab.domain.drop.entity;

import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DropTest {

    private static final Long SELLER_ID = 1L;

    @Test
    @DisplayName("createDraft는 DRAFT 상태의 DROP을 만든다")
    void createDraft() {
        // when
        Drop drop = Drop.createDraft(SELLER_ID);

        // then
        assertThat(drop.getStatus()).isEqualTo(DropStatus.DRAFT);
        assertThat(drop.getSellerId()).isEqualTo(SELLER_ID);
    }

    @Test
    @DisplayName("updateDraft는 전달된 값만 반영하고 null은 유지한다")
    void updateDraft_appliesOnlyProvidedFields() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusHours(2);
        drop.updateDraft("상품", "설명", 1L, 3000L, "안내", start, end);

        // when
        drop.updateDraft("새 이름", null, null, null, null, null, null);

        // then
        assertThat(drop.getName()).isEqualTo("새 이름");
        assertThat(drop.getDescription()).isEqualTo("설명");
        assertThat(drop.getCategoryId()).isEqualTo(1L);
        assertThat(drop.getShippingFee()).isEqualTo(3000L);
        assertThat(drop.getShippingNotice()).isEqualTo("안내");
        assertThat(drop.getSaleStartsAt()).isEqualTo(start);
        assertThat(drop.getSaleEndsAt()).isEqualTo(end);
    }

    @Test
    @DisplayName("DRAFT 상태가 아니면 수정할 수 없다")
    void updateDraft_rejectsNonDraft() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);

        // when & then
        assertThatThrownBy(() -> drop.updateDraft("이름", null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_EDITABLE);
    }

    @Test
    @DisplayName("소유자가 아니면 DROP_ACCESS_DENIED")
    void validateOwner_rejectsOtherSeller() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);

        // when & then
        assertThatThrownBy(() -> drop.validateOwner(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("소유자는 통과한다")
    void validateOwner_allowsOwner() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);

        // when & then
        assertThatCode(() -> drop.validateOwner(SELLER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시작 시각이 종료 시각보다 빠르지 않으면 INVALID_SCHEDULE")
    void updateDraft_rejectsInvalidSchedule() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        OffsetDateTime sameTime = OffsetDateTime.now();

        // when & then
        assertThatThrownBy(() -> drop.updateDraft(null, null, null, null, null, sameTime, sameTime))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("일정 한쪽만 있어도 저장된다")
    void updateDraft_allowsPartialSchedule() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);

        // when & then
        assertThatCode(() -> drop.updateDraft(null, null, null, null, null, OffsetDateTime.now(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("clearImages는 기존 이미지를 모두 제거한다")
    void clearImages() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        drop.addImage(DropImage.create(drop, "a.jpg", 0, "a"));
        drop.addImage(DropImage.create(drop, "b.jpg", 1, "b"));

        // when
        drop.clearImages();

        // then
        assertThat(drop.getImages()).isEmpty();
    }

    @Test
    @DisplayName("DRAFT는 WISH할 수 없고 DROP_NOT_FOUND로 숨긴다")
    void validateWishable_hidesDraft() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);

        // when & then
        assertThatThrownBy(() -> drop.validateWishable(OffsetDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("WISH이고 판매 시작 전이면 WISH할 수 있다")
    void validateWishable_allowsWishBeforeStart() {
        // given
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        Drop drop = wishDrop(start);

        // when & then
        assertThatCode(() -> drop.validateWishable(start.minusMinutes(1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("판매 시작 시각과 같거나 지나면 GRAB_ALREADY_STARTED")
    void validateWishable_rejectsWishAtOrAfterStart() {
        // given
        OffsetDateTime start = OffsetDateTime.now();
        Drop drop = wishDrop(start);

        // when & then
        assertThatThrownBy(() -> drop.validateWishable(start))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        assertThatThrownBy(() -> drop.validateWishable(start.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
    }

    @Test
    @DisplayName("GRAB·ENDED는 GRAB_ALREADY_STARTED")
    void validateWishable_rejectsGrabAndEnded() {
        // given
        for (DropStatus status : List.of(DropStatus.GRAB, DropStatus.ENDED)) {
            Drop drop = Drop.createDraft(SELLER_ID);
            ReflectionTestUtils.setField(drop, "status", status);

            // when & then
            assertThatThrownBy(() -> drop.validateWishable(OffsetDateTime.now()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        }
    }

    @Test
    @DisplayName("CANCELED는 DROP_NOT_WISHABLE")
    void validateWishable_rejectsCanceled() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        ReflectionTestUtils.setField(drop, "status", DropStatus.CANCELED);

        // when & then
        assertThatThrownBy(() -> drop.validateWishable(OffsetDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_WISHABLE);
    }

    @Test
    @DisplayName("isWishable은 상태·시각 기준으로 판정한다")
    void isWishable_followsActionsTable() {
        // given
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusHours(1);

        // then
        assertThat(dropWith(DropStatus.DRAFT, start, end).isWishable(start)).isFalse();
        assertThat(dropWith(DropStatus.WISH, start, end).isWishable(start.minusMinutes(1))).isTrue();
        assertThat(dropWith(DropStatus.WISH, start, end).isWishable(start)).isFalse();
        assertThat(dropWith(DropStatus.WISH, start, end).isWishable(start.plusSeconds(1))).isFalse();
        assertThat(dropWith(DropStatus.GRAB, start, end).isWishable(start)).isFalse();
        assertThat(dropWith(DropStatus.ENDED, start, end).isWishable(start)).isFalse();
        assertThat(dropWith(DropStatus.CANCELED, start, end).isWishable(start)).isFalse();
    }

    @Test
    @DisplayName("isWishable과 validateWishable은 모든 상태·시각에서 같은 결과를 낸다")
    void isWishable_matchesValidateWishable() {
        // given
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusHours(1);
        List<OffsetDateTime> times = List.of(start.minusMinutes(1), start, start.plusMinutes(1));

        // when & then
        for (DropStatus status : DropStatus.values()) {
            for (OffsetDateTime now : times) {
                Drop drop = dropWith(status, start, end);
                boolean wishable = drop.isWishable(now);
                boolean rejected = false;
                try {
                    drop.validateWishable(now);
                } catch (BusinessException e) {
                    rejected = true;
                }
                assertThat(wishable).as("status=%s, now=%s", status, now).isEqualTo(!rejected);
            }
        }
    }

    @Test
    @DisplayName("isOrderable은 GRAB + 판매 시각 범위 안 + 미품절일 때만 true")
    void isOrderable_followsActionsTable() {
        // given
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusHours(1);
        Drop grab = dropWith(DropStatus.GRAB, start, end);
        addOption(grab, 1000L, 5, true);

        // then: 시작 시각과 같으면 주문 가능, 종료 시각과 같으면 불가
        assertThat(grab.isOrderable(start)).isTrue();
        assertThat(grab.isOrderable(start.plusMinutes(30))).isTrue();
        assertThat(grab.isOrderable(start.minusSeconds(1))).isFalse();
        assertThat(grab.isOrderable(end)).isFalse();
        assertThat(grab.isOrderable(end.plusSeconds(1))).isFalse();

        for (DropStatus status : List.of(DropStatus.WISH, DropStatus.ENDED, DropStatus.CANCELED)) {
            Drop drop = dropWith(status, start, end);
            addOption(drop, 1000L, 5, true);
            assertThat(drop.isOrderable(start.plusMinutes(30))).as("status=%s", status).isFalse();
        }
    }

    @Test
    @DisplayName("품절이면 GRAB이어도 isOrderable은 false")
    void isOrderable_falseWhenSoldOut() {
        // given
        OffsetDateTime start = OffsetDateTime.now();
        Drop grab = dropWith(DropStatus.GRAB, start, start.plusHours(1));
        addOption(grab, 1000L, 0, true);

        // when & then
        assertThat(grab.isOrderable(start.plusMinutes(1))).isFalse();
    }

    @Test
    @DisplayName("isSoldOut은 활성 재고 합이 0이거나 활성 SKU가 없으면 true")
    void isSoldOut() {
        // given
        Drop withStock = Drop.createDraft(SELLER_ID);
        addOption(withStock, 1000L, 5, true);

        Drop zeroStock = Drop.createDraft(SELLER_ID);
        addOption(zeroStock, 1000L, 0, true);

        Drop noOptions = Drop.createDraft(SELLER_ID);

        Drop inactiveOnly = Drop.createDraft(SELLER_ID);
        addOption(inactiveOnly, 1000L, 5, false);

        // when & then
        assertThat(withStock.isSoldOut()).isFalse();
        assertThat(zeroStock.isSoldOut()).isTrue();
        assertThat(noOptions.isSoldOut()).isTrue();
        assertThat(inactiveOnly.isSoldOut()).isTrue();
    }

    @Test
    @DisplayName("getMinPrice는 활성 SKU 중 최저가이고, 활성 SKU가 없으면 null")
    void getMinPrice() {
        // given
        Drop drop = Drop.createDraft(SELLER_ID);
        addOption(drop, 30000L, 5, true);
        addOption(drop, 10000L, 5, true);
        addOption(drop, 5000L, 5, false);

        Drop inactiveOnly = Drop.createDraft(SELLER_ID);
        addOption(inactiveOnly, 1000L, 5, false);

        // when & then
        assertThat(drop.getMinPrice()).isEqualTo(10000L);
        assertThat(inactiveOnly.getMinPrice()).isNull();
    }

    private Drop dropWith(DropStatus status, OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        Drop drop = Drop.createDraft(SELLER_ID);
        ReflectionTestUtils.setField(drop, "status", status);
        ReflectionTestUtils.setField(drop, "saleStartsAt", saleStartsAt);
        ReflectionTestUtils.setField(drop, "saleEndsAt", saleEndsAt);
        return drop;
    }

    private void addOption(Drop drop, long unitPrice, int totalQuantity, boolean active) {
        DropOption option = DropOption.create(drop, unitPrice, totalQuantity, 0);
        option.updateActive(active);
        drop.addOption(option);
    }

    private Drop wishDrop(OffsetDateTime saleStartsAt) {
        Drop drop = Drop.createDraft(SELLER_ID);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);
        ReflectionTestUtils.setField(drop, "saleStartsAt", saleStartsAt);
        return drop;
    }
}
