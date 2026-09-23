package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;

import java.util.List;

// 판매자 권한과 DROP 소유권을 확인하고 주문 페이지를 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOrderService {

    private final OrderRepository orderRepository;

    public PageResponse<SellerOrderListResponse> findOrders(
            String email, Long dropId, OrderStatus orderStatus, PaymentStatus paymentStatus, int page, int size) {
        if (!orderRepository.existsApprovedSeller(email)) {
            throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        }
        if (dropId != null) {
            if (!orderRepository.existsDrop(dropId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!orderRepository.ownsDrop(email, dropId)) {
                throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
            }
        }
        Page<SellerOrderListResponse> orders = orderRepository.findSellerOrders(
                email, dropId, orderStatus == null ? null : orderStatus.name(),
                paymentStatus == null ? null : paymentStatus.name(), PageRequest.of(page, size));
        List<SellerOrderListResponse> content = orders.getContent();
        return new PageResponse<>(content, page, size, orders.getTotalElements(),
                orders.getTotalPages(), orders.hasNext());
    }
}
