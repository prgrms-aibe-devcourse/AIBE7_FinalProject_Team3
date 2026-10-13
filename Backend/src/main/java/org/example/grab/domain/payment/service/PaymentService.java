package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentEventSource;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.global.idempotency.RequestHashGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
    토스페이먼츠 결제 승인 흐름(PAYMENT.md 1.1). 이 클래스는 트랜잭션을 열지 않는다.
    PG 호출이 DB 트랜잭션과 행 잠금을 붙잡지 않도록, 검증·저장과 결과 반영은 PaymentTransactionService의 별도 트랜잭션으로 나눈다.
 */
@Service
public class PaymentService {

    // 진행 중인 이전 결제를 조회로 정리한 뒤 다시 검증하는 횟수. 정리되지 않으면 이중 결제를 막기 위해 거부한다.
    private static final int MAX_PREPARE_ATTEMPTS = 2;
    private static final String PROVIDER_PAYMENT_CONSTRAINT = "uq_payments_provider_payment";

    private final PaymentGateway paymentGateway;
    private final PaymentTransactionService transactionService;
    private final RequestHashGenerator requestHashGenerator;
    private final Clock clock;

    @Autowired
    public PaymentService(
            PaymentGateway paymentGateway,
            PaymentTransactionService transactionService,
            RequestHashGenerator requestHashGenerator
    ) {
        this(paymentGateway, transactionService, requestHashGenerator, Clock.systemUTC());
    }

    PaymentService(
            PaymentGateway paymentGateway,
            PaymentTransactionService transactionService,
            RequestHashGenerator requestHashGenerator,
            Clock clock
    ) {
        this.paymentGateway = paymentGateway;
        this.transactionService = transactionService;
        this.requestHashGenerator = requestHashGenerator;
        this.clock = clock;
    }

    public PaymentResponse pay(long buyerId, UUID orderId, String idempotencyKeyHeader, PaymentRequest request) {
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyHeader);
        RequestHash requestHash = requestHashGenerator.generate(request);

        for (int attempt = 0; attempt < MAX_PREPARE_ATTEMPTS; attempt++) {
            PaymentPreparation preparation = prepare(buyerId, orderId, idempotencyKey, requestHash, request);
            if (preparation instanceof PaymentPreparation.Replay replay) {
                return PaymentResponse.of(replay.payment(), replay.order());
            }
            if (preparation instanceof PaymentPreparation.ResolveReplay replay) {
                // 정리해도 결과를 모르면 UNKNOWN을 그대로 돌려준다. 재전송이므로 409로 거부하지 않는다.
                Payment payment = resolve(replay.paymentId(), replay.command());
                return PaymentResponse.of(payment, replay.order());
            }
            if (preparation instanceof PaymentPreparation.Ready ready) {
                Payment payment = confirm(ready.paymentId(), ready.command());
                return PaymentResponse.of(payment, ready.order());
            }
            PaymentPreparation.Resolve resolve = (PaymentPreparation.Resolve) preparation;
            resolve(resolve.paymentId(), resolve.command());
        }
        throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    /*
        다른 주문이 같은 paymentKey로 동시에 요청하면 주문 잠금이 달라 둘 다 prepare의 중복 확인을 통과한다.
        늦은 쪽은 저장에서 유니크 제약에 걸리므로 이미 처리된 paymentKey로 보고 거부한다.
     */
    private PaymentPreparation prepare(
            long buyerId, UUID orderId, IdempotencyKey idempotencyKey, RequestHash requestHash, PaymentRequest request) {
        try {
            return transactionService.prepare(buyerId, orderId, idempotencyKey, requestHash, request, now());
        } catch (DataIntegrityViolationException exception) {
            if (violates(exception, PROVIDER_PAYMENT_CONSTRAINT)) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
            }
            throw exception;
        }
    }

    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    /**
     * 진행 중 결제 정리 배치가 선점한 결제를 정리한다(ERD.md 3.2). 사용자 요청의 정리와 같은 규칙을 쓰고,
     * 남기는 결제 이벤트의 경로만 RECONCILIATION으로 한다. 트랜잭션 밖에서 호출해야 한다.
     */
    Payment reconcile(PaymentPreparation.Resolve target) {
        return resolve(target.paymentId(), target.command(), PaymentEventSource.RECONCILIATION);
    }

    // 승인 결과가 불명이면 바로 조회해 확정을 시도한다. 조회로도 모르면 UNKNOWN으로 남긴다.
    private Payment confirm(Long paymentId, PaymentConfirmCommand command) {
        return confirm(paymentId, command, null, PaymentEventSource.API);
    }

    // priorLookup: 이전 결제를 정리하며 승인 재요청을 판단한 조회 응답. 처음 승인하는 결제는 null
    private Payment confirm(
            Long paymentId, PaymentConfirmCommand command, PaymentGatewayResult priorLookup, PaymentEventSource source) {
        PaymentGatewayResult confirmResult = paymentGateway.confirm(command);
        PaymentGatewayResult lookupResult = null;
        if (confirmResult.outcome() == PaymentGatewayResult.Outcome.UNKNOWN) {
            lookupResult = paymentGateway.lookup(command.paymentKey());
        }
        return transactionService.applyResult(paymentId, priorLookup, confirmResult, lookupResult, now(), source);
    }

    /*
        이전 결제를 조회로 정리한다. PG가 승인 요청을 받지 않은 상태라면(연결 실패, 승인 요청 전 서버 중단)
        조회만으로는 PG가 인증을 만료시킬 때까지 결과를 알 수 없으므로, 같은 서버 멱등 키로 승인을 다시 요청한다.
        먼저 보낸 승인이 PG에 닿았다면 PG는 같은 키의 최초 결과를 돌려주므로 중복 승인되지 않는다.
        승인 재요청은 새로 청구하는 동작이므로, 조회를 마친 직후 현재 시각과 최신 주문 상태로 결제 가능한지 다시 판단한다.
        요청을 받은 시각이나 배치 시작 시각으로 미리 판단하면 조회·대기 중 마감이 지나도 승인을 요청해 보정 대상이 생긴다.
        결제할 수 없는 주문은 승인을 다시 요청하지 않고 UNKNOWN으로 남긴다. PG가 인증을 만료시킨다.
     */
    private Payment resolve(Long paymentId, PaymentConfirmCommand command) {
        return resolve(paymentId, command, PaymentEventSource.API);
    }

    private Payment resolve(Long paymentId, PaymentConfirmCommand command, PaymentEventSource source) {
        PaymentGatewayResult lookup = paymentGateway.lookup(command.paymentKey());
        if (lookup.awaitingConfirmation() && transactionService.isPayable(paymentId, now())) {
            return confirm(paymentId, command, lookup, source);
        }
        return transactionService.applyResult(paymentId, null, null, lookup, now(), source);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
