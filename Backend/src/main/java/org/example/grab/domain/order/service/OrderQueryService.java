package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderDetailResponse;
import org.example.grab.domain.order.dto.OrderSummaryResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderQueryRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final OrderQueryRepository orderQueryRepository;

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> findMyOrders(
            Long buyerId,
            OrderStatus status,
            int page,
            int size
    ) {
        Page<OrderSummaryResponse> result = orderRepository.findBuyerOrders(
                buyerId,
                status,
                PageRequest.of(page, size)
        ).map(OrderSummaryResponse::from);
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse findMyOrder(Long buyerId, Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        return OrderDetailResponse.from(
                order,
                orderQueryRepository.findLatestPaymentStatus(orderId),
                shipmentRepository.findByOrderId(orderId).orElse(null)
        );
    }
}
