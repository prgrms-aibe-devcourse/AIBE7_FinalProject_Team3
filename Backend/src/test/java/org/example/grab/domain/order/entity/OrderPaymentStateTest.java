package org.example.grab.domain.order.entity;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 결제 확정·만료에 쓰는 주문과 재고 예약의 상태 전이
class OrderPaymentStateTest {

    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2026-09-29T12:10:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-29T12:05:00Z");

    @Test
    @DisplayName("결제 마감 시각 정각부터 만료로 본다")
    void isPaymentExpired() {
        // given
        Order order = createOrder();

        // when & then
        assertThat(order.isPaymentExpired(EXPIRES_AT.minusNanos(1_000))).isFalse();
        assertThat(order.isPaymentExpired(EXPIRES_AT)).isTrue();
        assertThat(order.isPaymentExpired(EXPIRES_AT.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("결제 대기 주문을 결제 완료로 바꾸고 결제 시각을 기록한다")
    void markPaid() {
        // given
        Order order = createOrder();

        // when
        order.markPaid(NOW);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("결제 대기 주문을 만료로 바꾼다")
    void expire() {
        // given
        Order order = createOrder();

        // when
        order.expire();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문은 결제 완료나 만료로 바꿀 수 없다")
    void rejectsTransitionFromNonPending() {
        // given
        Order paid = createOrder();
        paid.markPaid(NOW);
        Order expired = createOrder();
        expired.expire();

        // when & then
        assertThatThrownBy(() -> paid.expire())
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
        assertThatThrownBy(() -> expired.markPaid(NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("재고 예약은 HELD에서 COMMITTED 또는 RELEASED로 한 번만 바뀐다")
    void reservationTransitions() {
        // given
        Order order = createOrder();
        StockReservation committed = StockReservation.hold(OrderItem.create(order, 1L, "검정 / L", 15000, 2), EXPIRES_AT);
        StockReservation released = StockReservation.hold(OrderItem.create(order, 2L, "흰색 / M", 15000, 1), EXPIRES_AT);

        // when
        committed.commit(NOW);
        released.release(ReleaseReason.EXPIRED, ReleaseDestination.AVAILABLE, NOW);

        // then
        assertThat(committed.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(committed.getCommittedAt()).isEqualTo(NOW);
        assertThat(released.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.getReleaseReason()).isEqualTo(ReleaseReason.EXPIRED);
        assertThat(released.getReleaseDestination()).isEqualTo(ReleaseDestination.AVAILABLE);
        assertThat(released.getReleasedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> committed.release(ReleaseReason.EXPIRED, ReleaseDestination.AVAILABLE, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> released.commit(NOW))
                .isInstanceOf(BusinessException.class);
    }

    private Order createOrder() {
        return Order.create(
                "ORD-20260929-000001",
                1L,
                100L,
                "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64),
                "한정 상품",
                "GRAB 판매자",
                30000,
                3000,
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울시 강남구", "101호", null),
                EXPIRES_AT
        );
    }
}
