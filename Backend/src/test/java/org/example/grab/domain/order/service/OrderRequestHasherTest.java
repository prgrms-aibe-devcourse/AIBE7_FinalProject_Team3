package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRequestHasherTest {

    private final OrderRequestHasher hasher = new OrderRequestHasher();

    @Test
    @DisplayName("옵션 순서가 달라도 같은 주문 요청 해시를 만든다")
    void createsStableHashRegardlessOfItemOrder() {
        // given
        OrderCreateRequest.ShippingAddress address = new OrderCreateRequest.ShippingAddress(
                "홍길동", "01012345678", "06236", "서울시 강남구", null, null
        );
        OrderCreateRequest first = new OrderCreateRequest(
                100L,
                List.of(new OrderCreateRequest.Item(2L, 1), new OrderCreateRequest.Item(1L, 2)),
                address
        );
        OrderCreateRequest second = new OrderCreateRequest(
                100L,
                List.of(new OrderCreateRequest.Item(1L, 2), new OrderCreateRequest.Item(2L, 1)),
                address
        );

        // when & then
        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }

    @Test
    @DisplayName("배송지가 다르면 다른 주문 요청 해시를 만든다")
    void includesShippingAddressInHash() {
        // given
        OrderCreateRequest first = request("101호");
        OrderCreateRequest second = request("102호");

        // when & then
        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(second));
    }

    private OrderCreateRequest request(String address2) {
        return new OrderCreateRequest(
                100L,
                List.of(new OrderCreateRequest.Item(1L, 1)),
                new OrderCreateRequest.ShippingAddress(
                        "홍길동", "01012345678", "06236", "서울시 강남구", address2, null
                )
        );
    }
}
