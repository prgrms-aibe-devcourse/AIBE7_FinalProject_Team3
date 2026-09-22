package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.domain.payment.repository.PaymentRepository.PaymentOrder;
import org.example.grab.domain.payment.repository.PaymentRepository.StoredPayment;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RequestHasher requestHasher;
    private final Clock clock;

    @Transactional
    public PaymentResponse pay(Long buyerId, Long orderId, String keyValue, PaymentRequest request) {
        String key = IdempotencyKey.from(keyValue).value();
        String hash = requestHasher.hash(request);
        paymentRepository.lockIdempotencyKey(key);

        StoredPayment existing = paymentRepository.findByIdempotencyKey(key);
        if (existing != null) {
            if (!existing.orderId().equals(orderId) || !existing.requestHash().equals(hash)) {
                throw new BusinessException(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
            }
            return toResponse(existing);
        }

        PaymentOrder order = paymentRepository.lockOrder(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!order.buyerId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        if (!"PAYMENT_PENDING".equals(order.status())) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (!now.isBefore(order.expiresAt())) {
            throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
        }

        PaymentResult result = PaymentResult.from(request.mockResult(), now);
        String providerPaymentId = "mock-" + UUID.randomUUID();
        Long paymentId = paymentRepository.createPayment(
                orderId, key, hash, order.amount(), result.status(), providerPaymentId,
                result.reconciliationStatus(), result.failureCode(), result.failureMessage(), result.approvedAt()
        );

        if (request.mockResult() == PaymentRequest.MockResult.SUCCESS) {
            paymentRepository.commitReservations(orderId, now);
            paymentRepository.markOrderPaid(orderId, now);
        }
        paymentRepository.saveEvent(
                paymentId,
                "api-" + key,
                "PAYMENT_" + result.status(),
                request.mockResult() == PaymentRequest.MockResult.TIMEOUT
                        ? "RECONCILIATION_REQUIRED" : "APPLIED",
                "{\"mockResult\":\"" + request.mockResult().name() + "\"}",
                now
        );
        return new PaymentResponse(
                paymentId, orderId, order.orderNumber(), order.amount(), result.status(), result.approvedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> findHistory(Long userId, Long orderId) {
        if (!paymentRepository.canAccess(userId, orderId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        return paymentRepository.findHistory(orderId);
    }

    private PaymentResponse toResponse(StoredPayment payment) {
        return new PaymentResponse(
                payment.id(), payment.orderId(), payment.orderNumber(), payment.amount(),
                payment.status(), payment.approvedAt()
        );
    }

    private record PaymentResult(
            String status,
            String reconciliationStatus,
            String failureCode,
            String failureMessage,
            OffsetDateTime approvedAt
    ) {
        private static PaymentResult from(PaymentRequest.MockResult result, OffsetDateTime now) {
            return switch (result) {
                case SUCCESS -> new PaymentResult("SUCCEEDED", "NONE", null, null, now);
                case FAILURE -> new PaymentResult(
                        "FAILED", "NONE", "MOCK_PAYMENT_FAILED", "Mock 결제 실패", null
                );
                case TIMEOUT -> new PaymentResult(
                        "UNKNOWN", "REQUIRED", "MOCK_TIMEOUT", "Mock PG 응답 시간 초과", null
                );
            };
        }
    }
}
