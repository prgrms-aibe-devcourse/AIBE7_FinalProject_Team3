package org.example.grab.domain.wish.service;

import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.entity.Wish;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class WishServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long DROP_ID = 100L;

    @Mock
    private WishTransactionService wishTransactionService;

    @InjectMocks
    private WishService wishService;

    @Test
    @DisplayName("등록 응답에 DROP ID·wished·등록 시각·안내 문구를 담는다")
    void register_returnsResponseWithNotice() {
        // given
        OffsetDateTime now = OffsetDateTime.now();
        given(wishTransactionService.register(USER_ID, DROP_ID))
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
        given(wishTransactionService.register(USER_ID, DROP_ID))
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
    @DisplayName("동시 충돌 후에도 활성 WISH가 없으면 공통 오류 코드로 변환한다")
    void register_conflictsWhenConcurrentWishMissing() {
        // given
        given(wishTransactionService.register(USER_ID, DROP_ID))
                .willThrow(new DataIntegrityViolationException("uq_wishes_user_drop"));
        given(wishTransactionService.findActiveAfterConcurrentInsert(USER_ID, DROP_ID))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> wishService.register(USER_ID, DROP_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("취소는 트랜잭션 서비스에 위임한다")
    void cancel_delegates() {
        // when
        wishService.cancel(USER_ID, DROP_ID);

        // then
        then(wishTransactionService).should().cancel(USER_ID, DROP_ID);
    }
}
