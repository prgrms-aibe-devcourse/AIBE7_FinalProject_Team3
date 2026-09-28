package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.MyOrderDetailResponse;
import org.example.grab.domain.order.dto.MyOrderListResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 구매자 본인의 주문 목록과 상세를 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;

    public PageResponse<MyOrderListResponse> findMyOrders(long buyerId, OrderStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = status == null
                ? orderRepository.findByBuyerIdOrderByCreatedAtDescIdDesc(buyerId, pageable)
                : orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDescIdDesc(buyerId, status, pageable);
        return new PageResponse<>(
                orders.map(MyOrderListResponse::from).getContent(),
                page,
                size,
                orders.getTotalElements(),
                orders.getTotalPages(),
                orders.hasNext()
        );
    }

    /** 다른 구매자의 주문도 ORDER_NOT_FOUND로 응답해 주문 존재 여부를 노출하지 않는다. */
    public MyOrderDetailResponse findMyOrder(long buyerId, UUID orderId) {
        Order order = orderRepository.findByUuidAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        return MyOrderDetailResponse.from(
                order,
                orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()),
                orderRepository.findLatestPaymentStatus(order.getId()).orElse(null),
                shipmentRepository.findByOrderId(order.getId()).orElse(null)
        );
    }
}
