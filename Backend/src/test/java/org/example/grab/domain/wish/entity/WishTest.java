package org.example.grab.domain.wish.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WishTest {

    private static final Long USER_ID = 1L;
    private static final Long DROP_ID = 100L;

    @Test
    @DisplayName("activate는 취소 시각이 없는 활성 WISH를 만든다")
    void activate() {
        // given
        OffsetDateTime now = OffsetDateTime.now();

        // when
        Wish wish = Wish.activate(USER_ID, DROP_ID, now);

        // then
        assertThat(wish.getUserId()).isEqualTo(USER_ID);
        assertThat(wish.getDropId()).isEqualTo(DROP_ID);
        assertThat(wish.getActivatedAt()).isEqualTo(now);
        assertThat(wish.getCanceledAt()).isNull();
        assertThat(wish.isActive()).isTrue();
    }

    @Test
    @DisplayName("cancel은 취소 시각을 기록하고 비활성으로 만든다")
    void cancel() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now();
        OffsetDateTime canceledAt = activatedAt.plusMinutes(1);
        Wish wish = Wish.activate(USER_ID, DROP_ID, activatedAt);

        // when
        wish.cancel(canceledAt);

        // then
        assertThat(wish.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(wish.isActive()).isFalse();
    }

    @Test
    @DisplayName("reactivate는 취소 시각을 지우고 등록 시각을 갱신한다")
    void reactivate() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now();
        OffsetDateTime reactivatedAt = activatedAt.plusHours(1);
        Wish wish = Wish.activate(USER_ID, DROP_ID, activatedAt);
        wish.cancel(activatedAt.plusMinutes(1));

        // when
        wish.reactivate(reactivatedAt);

        // then
        assertThat(wish.getCanceledAt()).isNull();
        assertThat(wish.getActivatedAt()).isEqualTo(reactivatedAt);
        assertThat(wish.isActive()).isTrue();
    }
}
