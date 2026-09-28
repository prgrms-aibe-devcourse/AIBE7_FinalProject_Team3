package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.IdempotencyResult;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderIdempotencyService idempotencyService;
    private final OrderCreateTransactionService transactionService;
    private final OrderItemRepository orderItemRepository;
    private final OrderRequestHasher requestHasher;

    public OrderCreateResponse create(Long buyerId, String idempotencyKeyHeader, OrderCreateRequest request) {
        validateUniqueOptions(request.items());
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyHeader);
        RequestHash requestHash = requestHasher.hash(request);
        IdempotencyResult<Order> idempotency = idempotencyService.check(buyerId, idempotencyKey, requestHash);
        if (idempotency.status() == IdempotencyResult.Status.REPLAY) {
            return toResponse(idempotency.getExistingResult().orElseThrow());
        }

        try {
            return toResponse(transactionService.create(buyerId, idempotencyKey, requestHash, request));
        } catch (DataIntegrityViolationException exception) {
            return resolveConcurrentRequest(buyerId, idempotencyKey, requestHash, exception);
        }
    }

    private OrderCreateResponse resolveConcurrentRequest(
            Long buyerId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash,
            DataIntegrityViolationException originalException
    ) {
        try {
            return toResponse(idempotencyService.resolveAfterConcurrentInsert(buyerId, idempotencyKey, requestHash));
        } catch (IllegalStateException exception) {
            throw originalException;
        }
    }

    private OrderCreateResponse toResponse(Order order) {
        return OrderCreateResponse.of(
                order,
                orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId())
        );
    }

    private void validateUniqueOptions(List<OrderCreateRequest.Item> items) {
        Set<Long> optionIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            if (!optionIds.add(items.get(index).optionId())) {
                throw new BusinessException(
                        CommonErrorCode.VALIDATION_FAILED,
                        List.of(new ErrorResponse.FieldError(
                                "items[" + index + "].optionId",
                                "같은 옵션을 중복해서 주문할 수 없습니다."
                        ))
                );
            }
        }
    }
}
