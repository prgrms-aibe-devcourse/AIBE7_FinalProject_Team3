package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.dto.SellerOrderListProjection;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

// DROP 소유권을 확인하고 판매자 주문을 조회한다.
// 판매자 인증과 승인 여부는 인증 시점에 CurrentSellerIdProvider(ROLE_SELLER)가 판정한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;

    public SellerOrderDetailResponse findOrder(long sellerId, UUID orderId) {
        Order order = orderRepository.findByUuid(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!orderRepository.ownsDrop(sellerId, order.getDropId())) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }

        return SellerOrderDetailResponse.from(
                order,
                orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()),
                orderRepository.findLatestPaymentStatus(order.getId()).orElse(null),
                shipmentRepository.findByOrderId(order.getId()).orElse(null));
    }

    public PageResponse<SellerOrderListResponse> findOrders(
            long sellerId, Long dropId, OrderStatus orderStatus, PaymentStatus paymentStatus, int page, int size) {
        if (dropId != null) {
            if (!orderRepository.existsDrop(dropId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!orderRepository.ownsDrop(sellerId, dropId)) {
                throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
            }
        }
        Page<SellerOrderListResponse> orders = orderRepository.findSellerOrders(
                sellerId, dropId, orderStatus == null ? null : orderStatus.name(),
                paymentStatus == null ? null : paymentStatus.name(), PageRequest.of(page, size))
                .map(SellerOrderService::toListResponse);
        List<SellerOrderListResponse> content = orders.getContent();
        return new PageResponse<>(content, page, size, orders.getTotalElements(),
                orders.getTotalPages(), orders.hasNext());
    }

    private static SellerOrderListResponse toListResponse(SellerOrderListProjection projection) {
        return new SellerOrderListResponse(
                projection.getOrderId(), projection.getOrderNumber(), projection.getDropId(),
                projection.getProductName(), projection.getSellerName(), projection.getOrderStatus(),
                projection.getPaymentStatus(), projection.getItemsAmount(), projection.getShippingAmount(),
                projection.getTotalAmount(), projection.getOrderedAt());
    }
}
