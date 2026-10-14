package org.example.grab.domain.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
    결제 배치의 한 주기(GR-65·GR-87, ERD.md 3.2). 진행 중 결제 정리 → 결제 대기 만료 → 결제 취소 재요청 순서로 실행한다.
    이 클래스는 트랜잭션을 열지 않는다. 건별 처리는 다른 서비스의 별도 트랜잭션에서 하고, PG 호출은 그 사이 트랜잭션 밖에서 한다.
    한 건의 실패가 나머지 건을 막지 않도록 건 단위로 예외를 격리하고, 한 단계의 실패가 다음 단계를 막지 않도록 단계도 격리한다.
 */
@Slf4j
@Service
public class PaymentBatchService {

    // 진행 중 결제 정리: 주기당 선점 건수 상한. 남은 대상은 다음 주기에 처리한다.
    static final int CLEANUP_BATCH_SIZE = 50;
    // 진행 중 결제 정리의 시간 예산. PG가 느리면 건수 상한만으로는 정리가 수십 분 걸려 만료가 늦어진다.
    // 예산을 넘기면 다음 결제부터는 처리하지 않고 만료로 넘어간다. 이미 시작한 한 건은 끝까지 기다린다.
    static final Duration CLEANUP_TIME_BUDGET = Duration.ofSeconds(5);
    // 생성(created_at) 후 이만큼 지나도 결과를 모르는 결제는 PG 인증 만료(10분)로도 정리되지 않은 것이라 운영자가 확인하도록 경고한다.
    static final Duration UNKNOWN_WARNING_AFTER = Duration.ofMinutes(10);
    // 결제 대기 만료: 후보를 키셋으로 읽는 페이지 크기
    static final int EXPIRY_BATCH_SIZE = 500;
    // 결제 취소 재요청: 주기당 선점 건수 상한과 시간 예산. 정리와 같은 이유로 둔다.
    static final int CANCEL_RETRY_BATCH_SIZE = 50;
    static final Duration CANCEL_RETRY_TIME_BUDGET = Duration.ofSeconds(5);
    // 생성 후 이만큼 지나도 진행 중인 취소는 운영자가 확인하도록 경고한다.
    static final Duration CANCEL_IN_PROGRESS_WARNING_AFTER = Duration.ofMinutes(10);

    private final PaymentTransactionService transactionService; // 정리 대상 선점, 값 조회, 결과 반영을 각각 트랜잭션에서 처리
    private final PaymentService paymentService; // PG 조회 및 승인 재요청 흐름을 처리
    private final OrderPaymentService orderPaymentService; // 만료 후보 조회와 주문별 만료를 처리
    private final OrderCancelTransactionService cancelTransactionService; // 취소 재요청 대상 선점과 값 조회
    private final OrderCancelService orderCancelService; // PG 취소 재요청과 결과 반영
    private final Clock clock; // 정리·취소 재요청 시간 예산의 경과 시간을 잰다

    @Autowired
    public PaymentBatchService(
            PaymentTransactionService transactionService,
            PaymentService paymentService,
            OrderPaymentService orderPaymentService,
            OrderCancelTransactionService cancelTransactionService,
            OrderCancelService orderCancelService
    ) {
        this(transactionService, paymentService, orderPaymentService, cancelTransactionService, orderCancelService,
                Clock.systemUTC());
    }

    PaymentBatchService(
            PaymentTransactionService transactionService,
            PaymentService paymentService,
            OrderPaymentService orderPaymentService,
            OrderCancelTransactionService cancelTransactionService,
            OrderCancelService orderCancelService,
            Clock clock
    ) {
        this.transactionService = transactionService;
        this.paymentService = paymentService;
        this.orderPaymentService = orderPaymentService;
        this.cancelTransactionService = cancelTransactionService;
        this.orderCancelService = orderCancelService;
        this.clock = clock;
    }

    /**
     * 한 주기를 실행한다. 정리를 만료보다 먼저 해, 마감 전에 승인된 결제를 먼저 주문 PAID로 반영한다.
     * 그러지 않으면 만료된 뒤 결제 SUCCEEDED·보정 REQUIRED로 남는다.
     * 취소 재요청은 만료 뒤에 두어 PG 지연이 재고 반환을 늦추지 않게 한다. 앞 단계가 실패해도 다음 단계는 실행한다.
     */
    public void run(OffsetDateTime now) {
        try {
            cleanUpInProgress(now);
        } catch (Exception e) {
            log.error("진행 중 결제 정리 실행 실패", e);
        }
        try {
            expireDue(now);
        } catch (Exception e) {
            log.error("결제 대기 만료 실행 실패", e);
        }
        try {
            retryCancellations(now);
        } catch (Exception e) {
            log.error("결제 취소 재요청 실행 실패", e);
        }
    }

    // ===== 진행 중 결제 정리 =====

    /**
     * 오래된 진행 중 결제(PENDING·UNKNOWN)를 선점해 PG 조회로 정리한다. 진행 중이 아닌 상태로 확정한 결제 수를 반환한다.
     * 시간 예산을 넘기면 남은 선점 결제는 처리하지 않는다. 선점 때 updated_at이 바뀌었으므로 60초 뒤 다시 선점된다.
     */
    int cleanUpInProgress(OffsetDateTime now) {
        Instant deadline = clock.instant().plus(CLEANUP_TIME_BUDGET);
        List<Long> paymentIds = transactionService.claimInProgress(now, CLEANUP_BATCH_SIZE);
        int settled = 0;
        int unsettled = 0;
        int failed = 0;
        int deferred = 0;
        for (int i = 0; i < paymentIds.size(); i++) {
            if (clock.instant().isAfter(deadline)) {
                deferred = paymentIds.size() - i; // 시간 예산을 넘겨 다음으로 넘긴 건수
                break;
            }
            switch (cleanUpOne(paymentIds.get(i), now)) {
                case SETTLED -> settled++; // 진행 중 상태가 아닌 것으로 확인된 건수
                case UNSETTLED -> unsettled++; // 처리 후에도 진행 중 상태인 건수
                case FAILED -> failed++; // 처리 중 예외가 발생한 건수
            }
        }
        if (deferred > 0) {
            log.warn("진행 중 결제 정리가 시간 예산({}초)을 넘겨 {}건을 다음으로 넘김. PG 지연을 확인하세요.",
                    CLEANUP_TIME_BUDGET.toSeconds(), deferred);
        }
        if (!paymentIds.isEmpty()) {
            log.info("진행 중 결제 정리: 확정 {}건, 미확정 {}건, 실패 {}건, 넘김 {}건", settled, unsettled, failed, deferred);
        }
        return settled;
    }

    // 실패한 결제는 updated_at이 선점 시각으로 바뀌어 있으므로 60초 뒤 다시 선점된다.
    private CleanupOutcome cleanUpOne(Long paymentId, OffsetDateTime now) {
        try {
            Optional<PaymentPreparation.Resolve> target = transactionService.findReconciliationTarget(paymentId);
            if (target.isEmpty()) {
                // 선점한 뒤 사용자 요청 등이 먼저 결과를 확정했다.
                return CleanupOutcome.SETTLED;
            }
            Payment payment = paymentService.reconcile(target.get());
            warnIfLongUnknown(payment, now);
            return payment.isInProgress() ? CleanupOutcome.UNSETTLED : CleanupOutcome.SETTLED;
        } catch (Exception e) {
            log.error("진행 중 결제 정리 실패: paymentId={}", paymentId, e);
            return CleanupOutcome.FAILED;
        }
    }

    private void warnIfLongUnknown(Payment payment, OffsetDateTime now) {
        if (payment.getStatus() != PaymentStatus.UNKNOWN || payment.getCreatedAt() == null) {
            return;
        }
        if (!payment.getCreatedAt().plus(UNKNOWN_WARNING_AFTER).isAfter(now)) {
            log.warn("생성 후 {}분이 지나도 결제 결과를 확인하지 못함: paymentId={}, createdAt={}",
                    UNKNOWN_WARNING_AFTER.toMinutes(), payment.getUuid(), payment.getCreatedAt());
        }
    }

    private enum CleanupOutcome {
        // 진행 중이 아닌 상태(SUCCEEDED·FAILED 등)로 확정됐다
        SETTLED,
        // PG 조회로도 결과를 몰라 PENDING·UNKNOWN으로 남았다
        UNSETTLED,
        // 예외로 정리하지 못했다
        FAILED
    }

    // ===== 결제 대기 만료 =====

    /**
     * 마감이 지난 결제 대기 주문을 모두 만료한다. 만료한 주문 수를 반환한다.
     * 후보는 잠그지 않고 키셋으로 끝까지 읽고, 주문마다 OrderPaymentService의 별도 트랜잭션에서 SKIP LOCKED로 만료한다.
     * 한 실행의 모든 페이지가 같은 now를 쓴다.
     */
    int expireDue(OffsetDateTime now) {
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        PaymentExpiryCandidate after = null;
        while (true) {
            List<PaymentExpiryCandidate> candidates =
                    orderPaymentService.findPaymentExpiryCandidates(now, after, EXPIRY_BATCH_SIZE);
            for (PaymentExpiryCandidate candidate : candidates) {
                PaymentExpiryResult result = expireOne(candidate.getId(), now);
                if (result == PaymentExpiryResult.EXPIRED) {
                    expired++;
                } else if (result == PaymentExpiryResult.SKIPPED_LOCKED) {
                    skipped++;
                } else if (result == null) {
                    failed++;
                }
            }
            // 잠겨 건너뛴 주문도 커서는 넘어간다. 같은 실행에서 다시 읽지 않고 다음 실행에서 조회된다.
            if (candidates.size() < EXPIRY_BATCH_SIZE) {
                break;
            }
            after = candidates.get(candidates.size() - 1);
        }
        if (expired + skipped + failed > 0) {
            log.info("결제 대기 만료: 만료 {}건, 잠금으로 건너뜀 {}건, 실패 {}건", expired, skipped, failed);
        }
        return expired;
    }

    // 실패하면 null을 돌려준다. 실패한 주문은 다음 실행에서 다시 조회된다.
    private PaymentExpiryResult expireOne(Long orderId, OffsetDateTime now) {
        try {
            return orderPaymentService.expireIfDueSkippingLocked(orderId, now);
        } catch (Exception e) {
            log.error("결제 대기 만료 실패: orderId={}", orderId, e);
            return null;
        }
    }

    // ===== 결제 취소 재요청 =====

    /**
     * 진행 중인 소비자 취소(REQUESTED·UNKNOWN)를 선점해 같은 서버 멱등 키로 PG에 다시 요청한다(ERD.md 3.3).
     * 성공·거절로 확정한 취소 수를 반환한다.
     * 시간 예산을 넘기면 남은 선점 취소는 처리하지 않는다. 선점 때 next_retry_at이 바뀌었으므로 1분 뒤 다시 선점된다.
     */
    int retryCancellations(OffsetDateTime now) {
        Instant deadline = clock.instant().plus(CANCEL_RETRY_TIME_BUDGET);
        List<Long> cancellationIds = cancelTransactionService.claimRetries(now, CANCEL_RETRY_BATCH_SIZE);
        int succeeded = 0;
        int rejected = 0;
        int unsettled = 0;
        int alreadySettled = 0;
        int failed = 0;
        int deferred = 0;
        for (int i = 0; i < cancellationIds.size(); i++) {
            if (clock.instant().isAfter(deadline)) {
                deferred = cancellationIds.size() - i;
                break;
            }
            switch (retryOne(cancellationIds.get(i), now)) {
                case SUCCEEDED -> succeeded++;
                case REJECTED -> rejected++;
                case UNSETTLED -> unsettled++;
                case ALREADY_SETTLED -> alreadySettled++;
                case FAILED -> failed++;
            }
        }
        if (deferred > 0) {
            log.warn("결제 취소 재요청이 시간 예산({}초)을 넘겨 {}건을 다음으로 넘김. PG 지연을 확인하세요.",
                    CANCEL_RETRY_TIME_BUDGET.toSeconds(), deferred);
        }
        if (!cancellationIds.isEmpty()) {
            log.info("결제 취소 재요청: 성공 {}건, 거절 {}건, 미확정 {}건, 이미 확정 {}건, 실패 {}건, 넘김 {}건",
                    succeeded, rejected, unsettled, alreadySettled, failed, deferred);
        }
        return succeeded + rejected;
    }

    // 실패한 취소는 next_retry_at이 선점 때 바뀌어 있으므로 1분 뒤 다시 선점된다.
    private CancelRetryOutcome retryOne(Long cancellationId, OffsetDateTime now) {
        try {
            Optional<OrderCancelTransactionService.CancelRetryTarget> target =
                    cancelTransactionService.findRetryTarget(cancellationId);
            if (target.isEmpty()) {
                // 선점한 뒤 사용자 재전송 등이 먼저 결과를 확정했다.
                return CancelRetryOutcome.ALREADY_SETTLED;
            }
            warnIfLongInProgress(target.get(), now);
            OrderCancelTransactionService.CancelOutcome outcome = orderCancelService.retry(target.get());
            if (outcome.rejected()) {
                // 주문은 결제 완료 상태로 남고 취소 요청이 풀린다. 사용자가 다시 취소할 수 있다.
                log.warn("결제 취소 재요청이 거절됨: cancellationId={}", cancellationId);
                return CancelRetryOutcome.REJECTED;
            }
            return outcome.response().refundStatus() == RefundStatus.SUCCEEDED
                    ? CancelRetryOutcome.SUCCEEDED
                    : CancelRetryOutcome.UNSETTLED;
        } catch (Exception e) {
            log.error("결제 취소 재요청 실패: cancellationId={}", cancellationId, e);
            return CancelRetryOutcome.FAILED;
        }
    }

    private void warnIfLongInProgress(OrderCancelTransactionService.CancelRetryTarget target, OffsetDateTime now) {
        if (target.createdAt() == null) {
            return;
        }
        if (!target.createdAt().plus(CANCEL_IN_PROGRESS_WARNING_AFTER).isAfter(now)) {
            log.warn("생성 후 {}분이 지나도 결제 취소 결과를 확정하지 못함: cancellationId={}, createdAt={}",
                    CANCEL_IN_PROGRESS_WARNING_AFTER.toMinutes(), target.cancellationId(), target.createdAt());
        }
    }

    private enum CancelRetryOutcome {
        // 취소 성공으로 확정했다
        SUCCEEDED,
        // PG가 취소를 거절해 FAILED로 확정했다
        REJECTED,
        // 결과를 몰라 UNKNOWN으로 남았다
        UNSETTLED,
        // 선점한 뒤 다른 요청이 먼저 확정했다
        ALREADY_SETTLED,
        // 예외로 처리하지 못했다
        FAILED
    }
}
