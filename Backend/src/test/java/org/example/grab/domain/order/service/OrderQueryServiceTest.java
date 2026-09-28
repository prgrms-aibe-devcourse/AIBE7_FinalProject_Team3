package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.MyOrderDetailResponse;
import org.example.grab.domain.order.dto.MyOrderListResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderQueryServiceTest {

    private static final long BUYER_ID = 1L;
    private static final OffsetDateTime PAYMENT_EXPIRES_AT = OffsetDateTime.parse("2026-09-28T12:10:00Z");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final OrderQueryService orderQueryService = new OrderQueryService(
            orderRepository, orderItemRepository, shipmentRepository);

    @Test
    @DisplayName("상태 필터가 없으면 구매자의 전체 주문을 최신순으로 조회한다")
    void findsAllMyOrdersWithoutStatus() {
        // given
        Order order = createOrder();
        PageRequest pageable = PageRequest.of(0, 20);
        given(orderRepository.findByBuyerIdOrderByCreatedAtDescIdDesc(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(order), pageable, 1));

        // when
        PageResponse<MyOrderListResponse> result = orderQueryService.findMyOrders(BUYER_ID, null, 0, 20);

        // then
        assertThat(result.content()).singleElement().satisfies(summary -> {
            assertThat(summary.orderId()).isEqualTo(order.getUuid());
            assertThat(summary.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(summary.totalAmount()).isEqualTo(33000);
        });
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        verify(orderRepository, never()).findByBuyerIdAndStatusOrderByCreatedAtDescIdDesc(
                BUYER_ID, null, pageable);
    }

    @Test
    @DisplayName("상태 필터가 있으면 해당 상태의 주문만 조회한다")
    void findsMyOrdersByStatus() {
        // given
        PageRequest pageable = PageRequest.of(1, 10);
        given(orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDescIdDesc(BUYER_ID, OrderStatus.PAID, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 10));

        // when
        PageResponse<MyOrderListResponse> result = orderQueryService.findMyOrders(
                BUYER_ID, OrderStatus.PAID, 1, 10);

        // then
        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(10);
    }

    @Test
    @DisplayName("내 주문 상세는 스냅샷 항목과 금액 내역, 결제·배송 정보를 반환한다")
    void findsMyOrderDetail() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        OrderItem item = OrderItem.create(order, 11L, "검정 / L", 15000, 2);
        Shipment shipment = Shipment.create(order, "CJ", "1234567890");
        given(orderRepository.findByUuidAndBuyerId(orderId, BUYER_ID)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrderIdOrderByIdAsc(null)).willReturn(List.of(item));
        given(orderRepository.findLatestPaymentStatus(null)).willReturn(Optional.of("PENDING"));
        given(shipmentRepository.findByOrderId(null)).willReturn(Optional.of(shipment));

        // when
        MyOrderDetailResponse result = orderQueryService.findMyOrder(BUYER_ID, orderId);

        // then
        assertThat(result.items()).singleElement().satisfies(detail -> {
            assertThat(detail.productName()).isEqualTo("한정 상품");
            assertThat(detail.optionName()).isEqualTo("검정 / L");
            assertThat(detail.subtotal()).isEqualTo(30000);
        });
        assertThat(result.itemsAmount()).isEqualTo(30000);
        assertThat(result.shippingAmount()).isEqualTo(3000);
        assertThat(result.totalAmount()).isEqualTo(33000);
        assertThat(result.paymentStatus()).isEqualTo("PENDING");
        assertThat(result.paymentExpiresAt()).isEqualTo(PAYMENT_EXPIRES_AT);
        assertThat(result.shipping()).isEqualTo(
                new MyOrderDetailResponse.Shipping("PAYMENT_PENDING", "CJ", "1234567890"));
    }

    @Test
    @DisplayName("다른 구매자의 주문은 존재 여부를 숨기고 ORDER_NOT_FOUND로 거부한다")
    void rejectsOthersOrderAsNotFound() {
        // given
        UUID orderId = UUID.randomUUID();
        given(orderRepository.findByUuidAndBuyerId(orderId, BUYER_ID)).willReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(() -> orderQueryService.findMyOrder(BUYER_ID, orderId));

        // then
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    private Order createOrder() {
        return Order.create(
                "ORD-20260928-000001",
                BUYER_ID,
                42L,
                "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64),
                "한정 상품",
                "GRAB 판매자",
                30000,
                3000,
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울시 강남구", "101호", null),
                PAYMENT_EXPIRES_AT
        );
    }
}
