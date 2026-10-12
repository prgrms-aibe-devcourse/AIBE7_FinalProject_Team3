package org.example.grab.domain.order.entity;

import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 소비자 주문 취소(GR-24)에 쓰는 주문과 재고 예약의 상태 전이
class OrderCancelStateTest {

    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2026-10-06T12:10:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T12:05:00Z");
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440001";
    private static final String HASH = "b".repeat(64);

    @Test
    @DisplayName("결제 대기·결제 완료·배송 준비 주문은 취소 요청을 기록한 뒤 취소할 수 있다")
    void cancelFromCancelableStatuses() {
        // given
        Order pending = createOrder();
        Order paid = createOrder();
        paid.markPaid(NOW);
        Order preparing = createOrder();
        preparing.markPaid(NOW);
        preparing.prepareShipment();

        for (Order order : new Order[]{pending, paid, preparing}) {
            // when
            order.requestCancel(KEY, HASH, "단순 변심");
            order.cancel(NOW);

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(order.getCanceledAt()).isEqualTo(NOW);
            assertThat(order.getCancelIdempotencyKey()).isEqualTo(KEY);
            assertThat(order.getCancelRequestHash()).isEqualTo(HASH);
            assertThat(order.getCancelReason()).isEqualTo("단순 변심");
        }
    }

    @Test
    @DisplayName("배송이 시작됐거나 이미 끝난 주문은 취소 요청을 거부한다")
    void rejectsNotCancelableStatuses() {
        // given
        Order shipped = createOrder();
        shipped.markPaid(NOW);
        shipped.prepareShipment();
        shipped.ship();
        Order expired = createOrder();
        expired.expire(EXPIRES_AT);
        Order canceled = createOrder();
        canceled.requestCancel(KEY, HASH, "단순 변심");
        canceled.cancel(NOW);

        // when & then
        for (Order order : new Order[]{shipped, expired, canceled}) {
            assertThat(order.isCancelable()).isFalse();
            assertThatThrownBy(() -> order.requestCancel("other-key", HASH, "단순 변심"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
    }

    @Test
    @DisplayName("진행 중인 취소 요청이 있으면 다른 취소 요청을 거부하고, 거절된 요청을 비우면 다시 받는다")
    void singleCancelRequestAtATime() {
        // given
        Order order = createOrder();
        order.markPaid(NOW);
        order.requestCancel(KEY, HASH, "단순 변심");

        // when & then: PG 결과를 기다리는 동안 다른 키의 요청은 충돌
        assertThatThrownBy(() -> order.requestCancel("other-key", HASH, "다른 사유"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.ORDER_STATUS_CONFLICT);

        // when: PG가 결제 취소를 거절했다
        order.clearCancelRequest();
        order.requestCancel("other-key", HASH, "다른 사유");

        // then: 주문은 결제 완료 그대로이고 새 요청이 기록된다
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getCancelIdempotencyKey()).isEqualTo("other-key");
        assertThat(order.getCancelReason()).isEqualTo("다른 사유");
    }

    @Test
    @DisplayName("취소 요청 없이 취소하거나, 취소된 주문의 요청 기록을 비울 수 없다")
    void cancelRequiresRequest() {
        // given
        Order notRequested = createOrder();
        Order canceled = createOrder();
        canceled.requestCancel(KEY, HASH, "단순 변심");
        canceled.cancel(NOW);

        // when & then
        assertThatThrownBy(() -> notRequested.cancel(NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
        assertThatThrownBy(canceled::clearCancelRequest)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("결제 완료 예약만 주문 취소 사유로 해제할 수 있다")
    void releaseCommittedReservation() {
        // given
        Order order = createOrder();
        StockReservation committed = StockReservation.hold(OrderItem.create(order, 1L, "검정 / L", 15000, 2), EXPIRES_AT);
        committed.commit(NOW);
        StockReservation held = StockReservation.hold(OrderItem.create(order, 2L, "흰색 / M", 15000, 1), EXPIRES_AT);

        // when
        committed.releaseCommitted(ReleaseDestination.AVAILABLE, NOW);

        // then
        assertThat(committed.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(committed.getReleaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELED);
        assertThat(committed.getReleaseDestination()).isEqualTo(ReleaseDestination.AVAILABLE);
        assertThat(committed.getReleasedAt()).isEqualTo(NOW);
        assertThat(committed.getCommittedAt()).isEqualTo(NOW);
        // 아직 결제되지 않은 예약이나 이미 해제된 예약은 이 경로로 해제하지 않는다
        assertThatThrownBy(() -> held.releaseCommitted(ReleaseDestination.AVAILABLE, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> committed.releaseCommitted(ReleaseDestination.AVAILABLE, NOW))
                .isInstanceOf(BusinessException.class);
    }

    private Order createOrder() {
        return Order.create(
                "ORD-20261006-000001",
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
