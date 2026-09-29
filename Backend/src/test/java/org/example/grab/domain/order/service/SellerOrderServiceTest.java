package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderShippingResponse;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.dto.SellerOrderListProjection;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.shipment.dto.ShipmentRegisterRequest;
import org.example.grab.domain.shipment.entity.Shipment;
import org.example.grab.domain.shipment.repository.ShipmentRepository;
import org.example.grab.domain.shipment.service.ShipmentRequestHasher;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SellerOrderServiceTest {

    private static final long SELLER_ID = 7L;

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ShipmentRequestHasher shipmentRequestHasher = new ShipmentRequestHasher();
    private final SellerOrderService sellerOrderService = new SellerOrderService(
            orderRepository,
            new OrderDetailReader(orderRepository, orderItemRepository, shipmentRepository),
            shipmentRepository,
            shipmentRequestHasher);

    @Test
    void returnsOrderDetailsForOwnedOrder() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        OrderItem item = OrderItem.create(order, 11L, "검정 / L", 15000, 2);
        when(orderRepository.findByUuid(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(null)).thenReturn(List.of(item));
        when(orderRepository.findLatestPaymentStatus(null)).thenReturn(Optional.of("SUCCEEDED"));
        when(shipmentRepository.findByOrderId(null)).thenReturn(Optional.empty());

        // when
        SellerOrderDetailResponse result = sellerOrderService.findOrder(SELLER_ID, orderId);

        // then
        assertThat(result.orderId()).isEqualTo(order.getUuid());
        assertThat(result.items()).singleElement().satisfies(detail -> {
            assertThat(detail.productName()).isEqualTo("한정 상품");
            assertThat(detail.optionName()).isEqualTo("검정 / L");
            assertThat(detail.subtotal()).isEqualTo(30000);
        });
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.shipping()).isEqualTo(new OrderShippingResponse(null, null, null, null));
        verify(orderRepository).ownsDrop(SELLER_ID, 42L);
    }

    @Test
    void rejectsOrderOwnedByAnotherSeller() {
        // given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByUuid(orderId)).thenReturn(Optional.of(createOrder()));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(false);

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.findOrder(SELLER_ID, orderId));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ACCESS_DENIED));
        verifyNoMoreInteractions(orderItemRepository, shipmentRepository);
    }

    @Test
    void returnsNotFoundForMissingOrder() {
        // given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByUuid(orderId)).thenReturn(Optional.empty());

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.findOrder(SELLER_ID, orderId));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verifyNoMoreInteractions(orderItemRepository, shipmentRepository);
    }

    @Test
    void preparesShipmentWhenPaidAndNoUnknownCancellationExists() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(orderRepository.hasUnknownOrderCancellation(100L)).thenReturn(false);

        // when
        var response = sellerOrderService.prepareShipment(SELLER_ID, orderId);

        // then
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.status()).isEqualTo("PREPARING");
        verify(orderRepository).findByUuidForUpdate(orderId);
        verify(orderRepository).hasUnknownOrderCancellation(100L);
    }

    @Test
    void rejectsShipmentPreparationWhenCancellationIsUnknown() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(orderRepository.hasUnknownOrderCancellation(100L)).thenReturn(true);

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.prepareShipment(SELLER_ID, orderId));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode())
                                .isEqualTo(OrderErrorCode.PAYMENT_CANCELLATION_UNKNOWN));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rejectsShipmentPreparationFromNonPaidState() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.prepareShipment(SELLER_ID, orderId));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION));
        verifyNoMoreInteractions(orderItemRepository, shipmentRepository);
    }

    @Test
    void registersShipmentAndMovesOrderToShipped() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PREPARING);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.empty());

        // when
        var response = sellerOrderService.registerShipment(SELLER_ID, orderId,
                "123e4567-e89b-12d3-a456-426614174000", new ShipmentRegisterRequest("CJ대한통운", "1234567890"));

        // then
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.status()).isEqualTo("SHIPPED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(shipmentRepository).save(org.mockito.ArgumentMatchers.argThat(shipment ->
                shipment.getCarrierCode().equals("CJ대한통운")
                        && shipment.getTrackingNumber().equals("1234567890")
                        && shipment.getIdempotencyKey().equals("123e4567-e89b-12d3-a456-426614174000")
                        && shipment.getRequestHash() != null
                        && shipment.getShippedAt() != null));
    }

    @Test
    void replaysSameShipmentRequest() {
        // given
        UUID orderId = UUID.randomUUID();
        var request = new ShipmentRegisterRequest("CJ대한통운", "1234567890");
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.DELIVERED);
        var hash = shipmentRequestHasher.hash(request).value();
        Shipment shipment = Shipment.register(order, request.carrier(), request.trackingNumber(),
                "123e4567-e89b-12d3-a456-426614174000", hash, OffsetDateTime.now());
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.of(shipment));

        // when
        var response = sellerOrderService.registerShipment(SELLER_ID, orderId,
                "123e4567-e89b-12d3-a456-426614174000", request);

        // then
        assertThat(response.status()).isEqualTo("SHIPPED");
        verifyNoMoreInteractions(orderItemRepository);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentShipmentRequest() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.SHIPPED);
        Shipment shipment = Shipment.register(order, "CJ대한통운", "1234567890",
                "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64), OffsetDateTime.now());
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.of(shipment));

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.registerShipment(SELLER_ID, orderId,
                "123e4567-e89b-12d3-a456-426614174000", new ShipmentRegisterRequest("CJ대한통운", "다른송장")));

        // then
        assertThat(exception).isInstanceOfSatisfying(BusinessException.class,
                actual -> assertThat(actual.getErrorCode()).isEqualTo(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY));
    }

    @Test
    void rejectsShipmentWhenOrderIsNotPreparing() {
        // given
        UUID orderId = UUID.randomUUID();
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        when(orderRepository.findByUuidForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.empty());

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.registerShipment(SELLER_ID, orderId,
                "123e4567-e89b-12d3-a456-426614174000", new ShipmentRegisterRequest("CJ대한통운", "1234567890")));

        // then
        assertThat(exception).isInstanceOfSatisfying(BusinessException.class,
                actual -> assertThat(actual.getErrorCode()).isEqualTo(CommonErrorCode.ORDER_STATUS_CONFLICT));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rejectsDropNotOwnedBySellerBeforeSearchingOrders() {
        // given
        when(orderRepository.existsDrop(42L)).thenReturn(true);
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(false);

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.findOrders(
                SELLER_ID, 42L, null, null, 0, 20));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED));

        verify(orderRepository).existsDrop(42L);
        verify(orderRepository).ownsDrop(SELLER_ID, 42L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void returnsNotFoundWhenDropDoesNotExist() {
        // given
        when(orderRepository.existsDrop(42L)).thenReturn(false);

        // when
        Throwable exception = catchThrowable(() -> sellerOrderService.findOrders(
                SELLER_ID, 42L, null, null, 0, 20));

        // then
        assertThat(exception)
                .isInstanceOfSatisfying(BusinessException.class,
                        actual -> assertThat(actual.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        verify(orderRepository).existsDrop(42L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void passesFiltersAndPaginationToRepositoryAndReturnsPageMetadata() {
        // given
        var order = new SellerOrderListResponse(UUID.randomUUID(), "GR-1", 42L, "상품", "판매자",
                "PAID", "SUCCEEDED", 1000, 2500, 3500, OffsetDateTime.parse("2026-09-23T12:00:00Z"));
        var pageRequest = PageRequest.of(2, 10);
        when(orderRepository.existsDrop(42L)).thenReturn(true);
        when(orderRepository.ownsDrop(SELLER_ID, 42L)).thenReturn(true);
        when(orderRepository.findSellerOrders(SELLER_ID, 42L, "PAID", "SUCCEEDED", pageRequest))
                .thenReturn(new PageImpl<>(List.of(toProjection(order)), pageRequest, 21));

        // when
        PageResponse<SellerOrderListResponse> result = sellerOrderService.findOrders(
                SELLER_ID, 42L, OrderStatus.PAID, PaymentStatus.SUCCEEDED, 2, 10);

        // then
        assertThat(result.content()).containsExactly(order);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isFalse();
        verify(orderRepository).existsDrop(42L);
        verify(orderRepository).ownsDrop(SELLER_ID, 42L);
        verify(orderRepository).findSellerOrders(SELLER_ID, 42L, "PAID", "SUCCEEDED", pageRequest);
    }

    private Order createOrder() {
        return Order.create(
                "GR-DETAIL-1", 8L, 42L, "idem-detail", "a".repeat(64), "한정 상품", "판매자",
                30000, 3000,
                ShippingAddress.of("받는 사람", "01000000000", "00000", "주소", null, null),
                OffsetDateTime.now().plusMinutes(15));
    }

    private SellerOrderListProjection toProjection(SellerOrderListResponse response) {
        return new SellerOrderListProjection() {
            @Override public UUID getOrderId() { return response.orderId(); }
            @Override public String getOrderNumber() { return response.orderNumber(); }
            @Override public long getDropId() { return response.dropId(); }
            @Override public String getProductName() { return response.productName(); }
            @Override public String getSellerName() { return response.sellerName(); }
            @Override public String getOrderStatus() { return response.orderStatus(); }
            @Override public String getPaymentStatus() { return response.paymentStatus(); }
            @Override public long getItemsAmount() { return response.itemsAmount(); }
            @Override public long getShippingAmount() { return response.shippingAmount(); }
            @Override public long getTotalAmount() { return response.totalAmount(); }
            @Override public OffsetDateTime getOrderedAt() { return response.orderedAt(); }
        };
    }
}
