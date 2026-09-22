package org.example.grab.domain.order.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    @DisplayName("주문 항목을 추가하면 주문과 양방향 관계가 설정된다")
    void addItemAssignsOrder() {
        // given
        Order order = createOrder(100L);
        OrderItem item = OrderItem.create(100L, 1001L, "블랙 / M", 12_900L, 2);

        // when
        order.addItem(item);

        // then
        assertThat(order.getItems()).containsExactly(item);
        assertThat(item.getOrder()).isSameAs(order);
        assertThat(item.calculateSubtotal()).isEqualTo(25_800L);
    }

    @Test
    @DisplayName("다른 DROP의 옵션은 주문에 추가할 수 없다")
    void addItemRejectsDifferentDrop() {
        // given
        Order order = createOrder(100L);
        OrderItem item = OrderItem.create(200L, 1001L, "블랙 / M", 12_900L, 1);

        // when, then
        assertThatThrownBy(() -> order.addItem(item))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문과 주문 항목의 DROP이 일치해야 합니다.");
    }

    @Test
    @DisplayName("주문 항목 수량은 1 이상이어야 한다")
    void createItemRejectsInvalidQuantity() {
        // when, then
        assertThatThrownBy(() -> OrderItem.create(100L, 1001L, "기본", 1_000L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문 수량은 1 이상이어야 합니다.");
    }

    private Order createOrder(Long dropId) {
        return Order.create(
                "ORD-20260922-0000000000000001",
                1L,
                dropId,
                "idempotency-key",
                "0".repeat(64),
                "한정판 상품",
                "GRAB",
                25_800L,
                3_000L,
                "홍길동",
                "01012345678",
                "06236",
                "서울특별시 강남구 테헤란로 1",
                "101호",
                null,
                OffsetDateTime.now().plusMinutes(10)
        );
    }
}
