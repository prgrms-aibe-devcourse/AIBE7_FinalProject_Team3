package org.example.grab.domain.wish;

/**
 * WISH 등록 응답과 DROP 상세 화면에서 공유하는 안내 문구.
 * 배포 없이 바꿀 필요가 없어 설정값이 아니라 상수로 둔다(WISH-007).
 */
public final class WishNotice {

    public static final String MESSAGE = "WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다.";

    private WishNotice() {
    }
}
