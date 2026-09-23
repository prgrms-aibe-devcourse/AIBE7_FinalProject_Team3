package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.global.idempotency.IdempotencyException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.IdempotencyResult;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderIdempotencyService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public IdempotencyResult<Order> check(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash
    ) {
        return checkExisting(buyerId, idempotencyKey, requestHash)
                .map(IdempotencyResult::replay)
                .orElseGet(IdempotencyResult::newRequest);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Order resolveAfterConcurrentInsert(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash
    ) {
        return checkExisting(buyerId, idempotencyKey, requestHash)
                .orElseThrow(() -> new IllegalStateException("동시 요청으로 생성된 주문을 찾을 수 없습니다."));
    }

    private Optional<Order> checkExisting(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash
    ) {
        return orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey.value())
                .map(order -> validateRequestHash(order, requestHash));
    }

    private Order validateRequestHash(Order order, RequestHash requestHash) {
        if (!order.getRequestHash().equals(requestHash.value())) {
            throw IdempotencyException.duplicateKey();
        }
        return order;
    }
}
