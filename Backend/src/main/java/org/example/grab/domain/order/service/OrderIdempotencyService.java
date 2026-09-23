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

    /**
     * 주문 생성 전에 멱등 키를 조회해 신규 요청, 재시도, 충돌로 분류한다.
     * 같은 키와 같은 요청 해시는 기존 주문을 재사용하고, 같은 키에 다른
     * 요청 해시가 들어오면 중복 키 충돌로 처리한다.
     */
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

    /**
     * 동시 요청 중 다른 트랜잭션이 먼저 저장한 주문을 새 트랜잭션에서 조회한다.
     * 호출자는 유니크 제약 위반으로 기존 트랜잭션이 종료된 뒤 호출해야 한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Order resolveAfterConcurrentInsert(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash
    ) {
        return checkExisting(buyerId, idempotencyKey, requestHash)
                .orElseThrow(() -> new IllegalStateException("동시 요청으로 생성된 주문을 찾을 수 없습니다."));
    }

    /** 구매자와 멱등 키로 주문을 조회한 뒤 요청 해시를 검증한다. */
    private Optional<Order> checkExisting(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash
    ) {
        return orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey.value())
                .map(order -> validateRequestHash(order, requestHash));
    }

    /** 같은 키라도 요청 내용이 다르면 기존 주문을 반환하지 않고 충돌 처리한다. */
    private Order validateRequestHash(Order order, RequestHash requestHash) {
        if (!order.getRequestHash().equals(requestHash.value())) {
            throw IdempotencyException.duplicateKey();
        }
        return order;
    }
}
