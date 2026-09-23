package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
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
    private final SellerOrderService sellerOrderService = new SellerOrderService(
            orderRepository, orderItemRepository, shipmentRepository);

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
                .thenReturn(new PageImpl<>(List.of(order), pageRequest, 21));

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
}
