package org.example.grab.domain.order.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MockDeliveryServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final MockDeliveryService mockDeliveryService = new MockDeliveryService(
            orderRepository, shipmentRepository);

    @Test
    void marksShippedOrderDeliveredAndRecordsTimestamp() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder(OrderStatus.SHIPPED);
        ReflectionTestUtils.setField(order, "id", 100L);
        Shipment shipment = Shipment.create(order, "MOCK", "GRAB-MOCK-1");
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.of(shipment));

        // when
        var response = mockDeliveryService.completeDelivery(orderId);

        // then
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.status()).isEqualTo("DELIVERED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(shipment.getDeliveredAt()).isNotNull();
        verify(orderRepository).findByUuidForUpdate(orderId);
        verify(shipmentRepository).findByOrderId(100L);
    }

    @Test
    void returnsSuccessWithoutChangingTimestampWhenAlreadyDelivered() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder(OrderStatus.DELIVERED);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));

        // when
        var response = mockDeliveryService.completeDelivery(orderId);

        // then
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.status()).isEqualTo("DELIVERED");
        verifyNoInteractions(shipmentRepository);
    }

    @Test
    void rejectsCompletionWhenOrderIsNotShipped() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder(OrderStatus.PREPARING);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));

        // when
        Throwable exception = catchThrowable(() -> mockDeliveryService.completeDelivery(orderId));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION));
        verifyNoInteractions(shipmentRepository);
    }

    private Order createOrder(OrderStatus status) {
        Order order = Order.create(
                "GR-MOCK-1", 8L, 42L, "idem-mock", "a".repeat(64), "상품", "판매자",
                30000, 3000,
                ShippingAddress.of("받는 사람", "01000000000", "00000", "주소", null, null),
                OffsetDateTime.now().plusMinutes(15));
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}
