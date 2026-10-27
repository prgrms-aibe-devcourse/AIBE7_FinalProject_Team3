package org.example.grab.domain.drop.dto;

import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse.Actions;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDropDetailResponseAtTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS = STARTS.plusDays(1);

    @Test
    @DisplayName("캐시한 응답을 판매 시작 뒤에 꺼내면 WISH 불가·주문 가능으로 다시 계산하고 serverTime을 바꾼다")
    void recomputesActionsAfterSaleStarts() {
        // given: 판매 시작 전에 만들어 캐시한 저장 상태 WISH 응답
        PublicDropDetailResponse cached = response(DropStatus.WISH, false).at(STARTS.minusMinutes(1));
        assertThat(cached.actions()).isEqualTo(new Actions(true, true, false));

        // when: 상태 전환 배치가 돌기 전, 판매 시작 시각이 지나 같은 캐시를 꺼낸다
        OffsetDateTime now = STARTS.plusSeconds(1);
        PublicDropDetailResponse served = cached.at(now);

        // then: 엔티티 규칙(Drop.isWishable·isOrderable)과 같이 서버 시각으로 판정한다
        assertThat(served.actions()).isEqualTo(new Actions(false, false, true));
        assertThat(served.serverTime()).isEqualTo(now);
        assertThat(served.status()).isEqualTo(DropStatus.WISH);
    }

    @Test
    @DisplayName("품절이거나 판매 종료 뒤면 주문 가능이 false다")
    void notOrderableWhenSoldOutOrEnded() {
        assertThat(response(DropStatus.GRAB, true).at(STARTS.plusHours(1)).actions().orderable()).isFalse();
        assertThat(response(DropStatus.GRAB, false).at(ENDS).actions().orderable()).isFalse();
        assertThat(response(DropStatus.CANCELED, false).at(STARTS.plusHours(1)).actions())
                .isEqualTo(new Actions(false, false, false));
    }

    private static PublicDropDetailResponse response(DropStatus status, boolean soldOut) {
        return new PublicDropDetailResponse(1L, "상품", "브랜드", "설명", List.of(), List.of(), List.of(), 1000L,
                new DropCategoryResponse(1L, "패션"), status, soldOut, 3L, "안내", STARTS, ENDS,
                new DropShippingResponse(3000L, "배송"), List.of(), List.of(), new Actions(false, false, false),
                STARTS.minusDays(1));
    }
}
