package org.example.grab.domain.wish.service;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.entity.Wish;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WishServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long DROP_ID = 100L;

    @Mock
    private DropService dropService;

    @Mock
    private WishTransactionService wishTransactionService;

    @InjectMocks
    private WishService wishService;

    @Test
    @DisplayName("DROP 판정이 실패하면 등록 저장을 시도하지 않는다")
    void register_skipsSaveWhenNotWishable() {
        // given
        willThrow(new BusinessException(DropErrorCode.GRAB_ALREADY_STARTED))
                .given(dropService).validateWishable(eq(DROP_ID), any());

        // when & then
        assertThatThrownBy(() -> wishService.register(USER_ID, DROP_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.GRAB_ALREADY_STARTED);
        verifyNoInteractions(wishTransactionService);
    }

    @Test
    @DisplayName("등록 응답에 DROP ID·wished·등록 시각·안내 문구를 담는다")
    void register_returnsResponseWithNotice() {
        // given
        OffsetDateTime now = OffsetDateTime.now();
        given(wishTransactionService.register(eq(USER_ID), eq(DROP_ID), any()))
                .willReturn(Wish.activate(USER_ID, DROP_ID, now));

        // when
        WishResponse response = wishService.register(USER_ID, DROP_ID);

        // then
        assertThat(response.dropId()).isEqualTo(DROP_ID);
        assertThat(response.wished()).isTrue();
        assertThat(response.wishedAt()).isEqualTo(now);
        assertThat(response.notice()).isEqualTo(WishNotice.MESSAGE);
    }

    @Test
    @DisplayName("동시 첫 등록 충돌은 새 트랜잭션 재조회 결과로 응답한다")
    void register_resolvesConcurrentInsert() {
        // given
        OffsetDateTime now = OffsetDateTime.now();
        given(wishTransactionService.register(eq(USER_ID), eq(DROP_ID), any()))
                .willThrow(new DataIntegrityViolationException("uq_wishes_user_drop"));
        given(wishTransactionService.findActiveAfterConcurrentInsert(USER_ID, DROP_ID))
                .willReturn(Optional.of(Wish.activate(USER_ID, DROP_ID, now)));

        // when
        WishResponse response = wishService.register(USER_ID, DROP_ID);

        // then
        assertThat(response.dropId()).isEqualTo(DROP_ID);
        assertThat(response.wishedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("동시 충돌 후에도 활성 WISH가 없으면 원래 예외를 다시 던진다")
    void register_rethrowsWhenConcurrentWishMissing() {
        // given
        DataIntegrityViolationException original = new DataIntegrityViolationException("uq_wishes_user_drop");
        given(wishTransactionService.register(eq(USER_ID), eq(DROP_ID), any())).willThrow(original);
        given(wishTransactionService.findActiveAfterConcurrentInsert(USER_ID, DROP_ID))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> wishService.register(USER_ID, DROP_ID)).isSameAs(original);
    }

    @Test
    @DisplayName("DROP 판정이 실패하면 취소를 시도하지 않는다")
    void cancel_skipsCancelWhenNotWishable() {
        // given
        willThrow(new BusinessException(DropErrorCode.DROP_NOT_WISHABLE))
                .given(dropService).validateWishable(eq(DROP_ID), any());

        // when & then
        assertThatThrownBy(() -> wishService.cancel(USER_ID, DROP_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_WISHABLE);
        verifyNoInteractions(wishTransactionService);
    }

    @Test
    @DisplayName("취소는 트랜잭션 서비스에 위임한다")
    void cancel_delegates() {
        // when
        wishService.cancel(USER_ID, DROP_ID);

        // then
        then(wishTransactionService).should().cancel(eq(USER_ID), eq(DROP_ID), any());
    }
}
