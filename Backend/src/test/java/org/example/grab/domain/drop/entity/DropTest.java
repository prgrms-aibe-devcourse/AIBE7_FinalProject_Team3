package org.example.grab.domain.drop.entity;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

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
}
