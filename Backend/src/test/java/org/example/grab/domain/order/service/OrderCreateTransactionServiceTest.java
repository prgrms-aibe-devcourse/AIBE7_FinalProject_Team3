package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderInventoryRepository;
import org.example.grab.domain.order.repository.OrderInventoryRepository.DropSnapshot;
import org.example.grab.domain.order.repository.OrderInventoryRepository.LockedOption;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderCreateTransactionServiceTest {

    private static final ProjectionFactory PROJECTION_FACTORY = new SpelAwareProxyProjectionFactory();

    private OrderInventoryRepository inventoryRepository;
    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private StockReservationRepository stockReservationRepository;
    private OrderNumberGenerator orderNumberGenerator;
    private OrderCreateTransactionService service;

    @BeforeEach
    void setUp() {
        inventoryRepository = mock(OrderInventoryRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        stockReservationRepository = mock(StockReservationRepository.class);
        orderNumberGenerator = mock(OrderNumberGenerator.class);
        given(orderNumberGenerator.generate()).willReturn("ORD-20260928-TEST");
        service = new OrderCreateTransactionService(
                inventoryRepository,
                orderRepository,
                orderItemRepository,
                stockReservationRepository,
                orderNumberGenerator
        );
    }

    @Test
    @DisplayName("잠근 옵션 재고를 선점하고 금액을 서버 가격으로 계산한다")
    void reservesStockAndCalculatesAmount() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        given(inventoryRepository.findDrop(100L)).willReturn(java.util.Optional.of(
                dropSnapshot(100L, "GRAB", "한정판", "판매자", 3000, now.minusMinutes(1), now.plusHours(1))
        ));
        given(inventoryRepository.lockOptions(100L, List.of(1001L))).willReturn(List.of(
                lockedOption(1001L, 129000, 10, 1, 0, 0, true, "블랙 / M")
        ));
        given(orderRepository.saveAndFlush(any(Order.class))).willAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1L);
            return order;
        });

        // when
        Order order = service.create(1L, idempotencyKey(), requestHash(), request(2));

        // then
        assertThat(order.getItemsAmount()).isEqualTo(258000);
        assertThat(order.getShippingAmount()).isEqualTo(3000);
        assertThat(order.getTotalAmount()).isEqualTo(261000);
        verify(inventoryRepository).increaseReservedQuantity(1001L, 2);
        verify(orderItemRepository).saveAllAndFlush(any());
        verify(stockReservationRepository).saveAll(any());
    }

    @Test
    @DisplayName("옵션 하나라도 재고가 부족하면 주문과 선점을 수행하지 않는다")
    void rejectsInsufficientStock() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        given(inventoryRepository.findDrop(100L)).willReturn(java.util.Optional.of(
                dropSnapshot(100L, "GRAB", "한정판", "판매자", 3000, now.minusMinutes(1), now.plusHours(1))
        ));
        given(inventoryRepository.lockOptions(100L, List.of(1001L))).willReturn(List.of(
                lockedOption(1001L, 129000, 2, 1, 0, 0, true, "블랙 / M")
        ));

        // when & then
        assertThatThrownBy(() -> service.create(1L, idempotencyKey(), requestHash(), request(2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(inventoryRepository, never()).increaseReservedQuantity(any(), any(Integer.class));
    }

    @Test
    @DisplayName("판매 시작 전 DROP 주문을 거부한다")
    void rejectsBeforeSaleStart() {
        // given
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        given(inventoryRepository.findDrop(100L)).willReturn(java.util.Optional.of(
                dropSnapshot(100L, "GRAB", "한정판", "판매자", 3000, now.plusHours(1), now.plusHours(2))
        ));

        // when & then
        assertThatThrownBy(() -> service.create(1L, idempotencyKey(), requestHash(), request(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(OrderErrorCode.SALE_NOT_STARTED));
    }

    private OrderCreateRequest request(int quantity) {
        return new OrderCreateRequest(
                100L,
                List.of(new OrderCreateRequest.Item(1001L, quantity)),
                new OrderCreateRequest.ShippingAddress(
                        "홍길동", "01012345678", "06236", "서울시 강남구", "101호", null
                )
        );
    }

    private IdempotencyKey idempotencyKey() {
        return IdempotencyKey.from("550e8400-e29b-41d4-a716-446655440000");
    }

    private RequestHash requestHash() {
        return RequestHash.from("a".repeat(64));
    }

    private DropSnapshot dropSnapshot(
            Long id,
            String status,
            String productName,
            String sellerName,
            long shippingFee,
            OffsetDateTime saleStartsAt,
            OffsetDateTime saleEndsAt
    ) {
        return PROJECTION_FACTORY.createProjection(DropSnapshot.class, Map.of(
                "id", id,
                "status", status,
                "productName", productName,
                "sellerName", sellerName,
                "shippingFee", shippingFee,
                "saleStartsAt", saleStartsAt.toInstant(),
                "saleEndsAt", saleEndsAt.toInstant()
        ));
    }

    private LockedOption lockedOption(
            Long id,
            long unitPrice,
            int totalQuantity,
            int reservedQuantity,
            int soldQuantity,
            int withheldQuantity,
            boolean active,
            String optionName
    ) {
        return PROJECTION_FACTORY.createProjection(LockedOption.class, Map.of(
                "id", id,
                "unitPrice", unitPrice,
                "totalQuantity", totalQuantity,
                "reservedQuantity", reservedQuantity,
                "soldQuantity", soldQuantity,
                "withheldQuantity", withheldQuantity,
                "active", active,
                "optionName", optionName
        ));
    }
}
