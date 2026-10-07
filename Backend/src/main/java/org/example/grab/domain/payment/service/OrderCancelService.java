package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.idempotency.RequestHashGenerator;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/*
    소비자 주문 취소(ORDER.md 1.4, ERD.md 3.3). 이 클래스는 트랜잭션을 열지 않는다.
    PG 결제 취소가 DB 트랜잭션과 행 잠금을 붙잡지 않도록, 검증·기록과 결과 반영은 OrderCancelTransactionService의 별도 트랜잭션으로 나눈다.
    주문·예약·재고 변경은 OrderPaymentService를 거친다.
 */
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderCancelTransactionService transactionService;
    private final PaymentGateway paymentGateway;
    private final RequestHashGenerator requestHashGenerator;

    public OrderCancelResponse cancel(long buyerId, UUID orderId, String idempotencyKeyHeader, OrderCancelRequest request) {
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyHeader);
        RequestHash requestHash = requestHashGenerator.generate(request);

        CancelPreparation preparation = transactionService.prepare(
                buyerId, orderId, idempotencyKey, requestHash, request, now());
        if (preparation instanceof CancelPreparation.Done done) {
            return done.response();
        }

        CancelPreparation.RequestPgCancel pgCancel = (CancelPreparation.RequestPgCancel) preparation;
        PaymentCancelResult result = paymentGateway.cancel(pgCancel.command());
        OrderCancelTransactionService.CancelOutcome outcome =
                transactionService.applyResult(pgCancel.cancellationId(), result, now());
        if (outcome.rejected()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
        }
        return outcome.response();
    }

    // PostgreSQL TIMESTAMPTZ 정밀도(마이크로초)와 UTC로 맞춰, 최초 응답과 재전송 응답의 canceledAt이 같게 한다.
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
