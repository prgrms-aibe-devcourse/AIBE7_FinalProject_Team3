package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
    진행 중 결제 정리 배치의 한 번 실행(GR-65, ERD.md 3.2). 이 클래스는 트랜잭션을 열지 않는다.
    대상 선점·값 읽기·결과 반영은 PaymentTransactionService의 별도 트랜잭션에서 하고, PG 호출은 그 사이 트랜잭션 밖에서 한다.
    한 결제의 실패가 나머지 결제의 정리를 막지 않도록 결제 단위로 예외를 격리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInProgressCleanupService {

    // PG 호출 시간이 같은 주기의 만료 실행을 늦추지 않도록 주기당 정리 건수를 제한한다. 남은 대상은 다음 주기에 처리한다.
    static final int BATCH_SIZE = 50;
    // 생성 후 이만큼 지나도 결과를 모르는 결제는 PG 인증 만료(10분)로도 정리되지 않은 것이라 운영자가 확인하도록 경고한다.
    static final Duration UNKNOWN_WARNING_AFTER = Duration.ofMinutes(10);

    private final PaymentTransactionService transactionService;
    private final PaymentService paymentService;

    /** 오래된 진행 중 결제를 선점해 PG 조회로 정리한다. 진행 중이 아닌 상태로 확정한 결제 수를 반환한다. */
    public int cleanUp(OffsetDateTime now) {
        List<Long> paymentIds = transactionService.claimInProgress(now, BATCH_SIZE);
        int settled = 0;
        int unsettled = 0;
        int failed = 0;
        for (Long paymentId : paymentIds) {
            switch (cleanUpOne(paymentId, now)) {
                case SETTLED -> settled++;
                case UNSETTLED -> unsettled++;
                case FAILED -> failed++;
            }
        }
        if (!paymentIds.isEmpty()) {
            log.info("진행 중 결제 정리: 확정 {}건, 미확정 {}건, 실패 {}건", settled, unsettled, failed);
        }
        return settled;
    }

    // 실패한 결제는 updated_at이 선점 시각으로 바뀌어 있으므로 60초 뒤 다시 선점된다.
    private Outcome cleanUpOne(Long paymentId, OffsetDateTime now) {
        try {
            Optional<ReconciliationTarget> target = transactionService.findReconciliationTarget(paymentId, now);
            if (target.isEmpty()) {
                // 선점한 뒤 사용자 요청 등이 먼저 결과를 확정했다.
                return Outcome.SETTLED;
            }
            Payment payment = paymentService.reconcile(target.get());
            warnIfLongUnknown(payment, target.get(), now);
            return payment.isInProgress() ? Outcome.UNSETTLED : Outcome.SETTLED;
        } catch (Exception e) {
            log.error("진행 중 결제 정리 실패: paymentId={}", paymentId, e);
            return Outcome.FAILED;
        }
    }

    private void warnIfLongUnknown(Payment payment, ReconciliationTarget target, OffsetDateTime now) {
        if (payment.getStatus() != PaymentStatus.UNKNOWN || target.createdAt() == null) {
            return;
        }
        if (!target.createdAt().plus(UNKNOWN_WARNING_AFTER).isAfter(now)) {
            log.warn("생성 후 {}분이 지나도 결제 결과를 확인하지 못함: paymentId={}, createdAt={}",
                    UNKNOWN_WARNING_AFTER.toMinutes(), payment.getUuid(), target.createdAt());
        }
    }

    private enum Outcome {
        // 진행 중이 아닌 상태(SUCCEEDED·FAILED 등)로 확정됐다
        SETTLED,
        // PG 조회로도 결과를 몰라 PENDING·UNKNOWN으로 남았다
        UNSETTLED,
        // 예외로 정리하지 못했다
        FAILED
    }
}
