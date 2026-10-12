package org.example.grab.domain.wish.service;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.wish.entity.Wish;
import org.example.grab.domain.wish.repository.WishRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WishTransactionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long DROP_ID = 100L;

    @Mock
    private WishRepository wishRepository;

    @Mock
    private DropService dropService;

    @InjectMocks
    private WishTransactionService wishTransactionService;

    @Test
    @DisplayName("WISH가 없으면 새로 저장한다")
    void register_createsNewWish() {
        // given
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.empty());
        given(wishRepository.saveAndFlush(any(Wish.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Wish wish = wishTransactionService.register(USER_ID, DROP_ID);

        // then
        assertThat(wish.getUserId()).isEqualTo(USER_ID);
        assertThat(wish.getDropId()).isEqualTo(DROP_ID);
        assertThat(wish.getActivatedAt()).isBetween(
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(wish.isActive()).isTrue();
    }

    @Test
    @DisplayName("취소된 WISH 재등록은 새 행을 만들지 않고 재활성화한다")
    void register_reactivatesCanceledWish() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now().minusDays(1);
        OffsetDateTime canceledAt = activatedAt.plusMinutes(1);
        Wish canceled = Wish.activate(USER_ID, DROP_ID, activatedAt);
        canceled.cancel(canceledAt);
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.of(canceled));
        given(wishRepository.saveAndFlush(any(Wish.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Wish wish = wishTransactionService.register(USER_ID, DROP_ID);

        // then
        assertThat(wish).isSameAs(canceled);
        assertThat(wish.getCanceledAt()).isNull();
        assertThat(wish.getActivatedAt()).isAfter(canceledAt);
    }

    @Test
    @DisplayName("이미 활성인 WISH 재등록은 기존 등록 시각을 유지한다")
    void register_keepsActiveWish() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now().minusHours(1);
        Wish active = Wish.activate(USER_ID, DROP_ID, activatedAt);
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.of(active));
        given(wishRepository.saveAndFlush(any(Wish.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Wish wish = wishTransactionService.register(USER_ID, DROP_ID);

        // then
        assertThat(wish).isSameAs(active);
        assertThat(wish.getActivatedAt()).isEqualTo(activatedAt);
        assertThat(wish.isActive()).isTrue();
    }

    @Test
    @DisplayName("활성 WISH가 있으면 취소 시각을 기록한다")
    void cancel_marksActiveWish() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now().minusHours(1);
        Wish active = Wish.activate(USER_ID, DROP_ID, activatedAt);
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.of(active));

        // when
        wishTransactionService.cancel(USER_ID, DROP_ID);

        // then
        assertThat(active.getCanceledAt()).isBetween(
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(active.isActive()).isFalse();
    }

    @Test
    @DisplayName("활성 WISH가 없으면 취소는 아무 것도 하지 않는다(멱등)")
    void cancel_doesNothingWithoutActiveWish() {
        // given
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> wishTransactionService.cancel(USER_ID, DROP_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 취소된 WISH는 다시 취소하지 않는다")
    void cancel_keepsAlreadyCanceledWish() {
        // given
        OffsetDateTime activatedAt = OffsetDateTime.now().minusDays(1);
        OffsetDateTime canceledAt = activatedAt.plusMinutes(1);
        Wish canceled = Wish.activate(USER_ID, DROP_ID, activatedAt);
        canceled.cancel(canceledAt);
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.of(canceled));

        // when
        wishTransactionService.cancel(USER_ID, DROP_ID);

        // then
        assertThat(canceled.getCanceledAt()).isEqualTo(canceledAt);
    }

    @Test
    @DisplayName("동시 삽입 후 재조회는 활성 WISH만 반환한다")
    void findActiveAfterConcurrentInsert_filtersCanceled() {
        // given
        Wish canceled = Wish.activate(USER_ID, DROP_ID, OffsetDateTime.now().minusDays(1));
        canceled.cancel(OffsetDateTime.now().minusHours(1));
        given(wishRepository.findByUserIdAndDropId(USER_ID, DROP_ID)).willReturn(Optional.of(canceled));

        // when & then
        assertThat(wishTransactionService.findActiveAfterConcurrentInsert(USER_ID, DROP_ID)).isEmpty();
    }

    @Test
    @DisplayName("잠금을 얻은 뒤 DROP 상태를 검증하고 판매 시작 후에는 저장하지 않는다")
    void register_validatesAfterLock() {
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.empty());
        willThrow(new BusinessException(DropErrorCode.GRAB_ALREADY_STARTED))
                .given(dropService).validateWishable(eq(DROP_ID), any());

        assertThatThrownBy(() -> wishTransactionService.register(USER_ID, DROP_ID))
                .isInstanceOf(BusinessException.class);
        InOrder order = inOrder(wishRepository, dropService);
        order.verify(wishRepository).findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID);
        order.verify(dropService).validateWishable(eq(DROP_ID), any());
        verify(wishRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("활성화 시각이 현재보다 미래여도 취소 시각은 활성화 시각보다 빠르지 않다")
    void cancel_neverPrecedesActivation() {
        OffsetDateTime activatedAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        Wish active = Wish.activate(USER_ID, DROP_ID, activatedAt);
        given(wishRepository.findByUserIdAndDropIdForUpdate(USER_ID, DROP_ID)).willReturn(Optional.of(active));

        wishTransactionService.cancel(USER_ID, DROP_ID);

        assertThat(active.getCanceledAt()).isEqualTo(activatedAt);
    }
}
