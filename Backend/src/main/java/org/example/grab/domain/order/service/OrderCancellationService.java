package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderCancelRequest;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderCancellationRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.OrderStockRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private final OrderRepository orderRepository;
    private final OrderCancellationRepository cancellationRepository;
    private final OrderStockRepository orderStockRepository;
    private final RequestHasher requestHasher;
    private final Clock clock;

    @Transactional
    public OrderStatusResponse cancel(Long buyerId, Long orderId, String keyValue, OrderCancelRequest request) {
        String key = IdempotencyKey.from(keyValue).value();
        String hash = requestHasher.hash(request);
        if (!orderStockRepository.lockBuyer(buyerId)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        var previousRequest = cancellationRepository.findRequest(buyerId, key);
        if (previousRequest != null) {
            if (!previousRequest.orderId().equals(orderId) || !previousRequest.requestHash().equals(hash)) {
                throw new BusinessException(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
            }
            Order previousOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
            return OrderStatusResponse.from(previousOrder);
        }

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.PAYMENT_PENDING && previousStatus != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (previousStatus == OrderStatus.PAID) {
            cancellationRepository.cancelMockPayment(
                    orderId, buyerId, key, hash, request.reason(), now
            );
        }
        cancellationRepository.releaseReservations(orderId, now);
        order.cancel(now);
        cancellationRepository.saveRequest(orderId, buyerId, key, hash, request.reason());
        return OrderStatusResponse.from(order);
    }
}
