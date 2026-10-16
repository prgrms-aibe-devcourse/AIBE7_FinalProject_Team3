package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/*
    보정 환불 한 건을 처리한다(GR-88, ERD.md 3.3). 이 클래스는 트랜잭션을 열지 않는다.
    PG 결제 조회·취소는 트랜잭션 밖에서 하고, 결과 반영은 CompensationRefundTransactionService의 별도 트랜잭션에서 한다.
    PG 응답의 주문번호가 서버 주문과 다른 승인을 거르려고, 시도마다 결제 조회를 먼저 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationRefundService {

    // PG 결제 조회의 상태 원문 중 이미 취소된 결제
    private static final String PG_CANCELED_STATUS = "CANCELED";

    private final CompensationRefundTransactionService transactionService;
    private final PaymentGateway paymentGateway;

    /**
     * PG 결제를 조회하고 승인·취소 상태면 기록의 서버 멱등 키로 전액 취소를 요청해 결과를 반영한다.
     * 이미 취소된 결제도 취소를 요청한다. 조회 결과에는 취소 시각·거래 키가 없고, PG가 최초 결과를 돌려주거나
     * ALREADY_CANCELED_PAYMENT 뒤 조회로 확인해 주므로 다시 환불되지 않는다.
     */
    public RefundOutcome refund(CompensationRefundTransactionService.RefundTarget target) {
        PaymentGatewayResult lookup = paymentGateway.lookup(target.command().paymentKey());
        if (lookup.outcome() == PaymentGatewayResult.Outcome.APPROVED
                && !target.orderNumber().equals(lookup.orderId())) {
            log.error("PG 결제의 주문번호가 서버 주문과 달라 보정 환불하지 않음. 운영자 확인 필요: cancellationId={}, orderNumber={}",
                    target.cancellationId(), target.orderNumber());
            PaymentCancellationStatus status = transactionService.abandonOrderMismatch(target.cancellationId(), now());
            return status == PaymentCancellationStatus.FAILED ? RefundOutcome.ORDER_MISMATCH : RefundOutcome.of(status);
        }
        if (!isRefundable(lookup)) {
            // 조회 실패나 판단할 수 없는 상태다. 취소하지 않고 선점 때 바뀐 next_retry_at에 다시 시도한다.
            log.warn("보정 환불 전 PG 결제 상태를 확인하지 못함: cancellationId={}, pgStatus={}, failureCode={}",
                    target.cancellationId(), lookup.pgStatus(), lookup.failureCode());
            return RefundOutcome.UNSETTLED;
        }

        PaymentCancelResult result = paymentGateway.cancel(target.command());
        PaymentCancellationStatus status = transactionService.applyResult(target.cancellationId(), result, now());
        if (status == PaymentCancellationStatus.FAILED) {
            log.error("보정 환불이 PG에서 거절됨. 운영자 확인 필요: cancellationId={}, failureCode={}",
                    target.cancellationId(), result.failureCode());
        }
        return RefundOutcome.of(status);
    }

    private static boolean isRefundable(PaymentGatewayResult lookup) {
        return lookup.outcome() == PaymentGatewayResult.Outcome.APPROVED
                || (lookup.outcome() == PaymentGatewayResult.Outcome.NOT_APPROVED
                && PG_CANCELED_STATUS.equals(lookup.pgStatus()));
    }

    // PostgreSQL TIMESTAMPTZ 정밀도(마이크로초)와 UTC로 맞춘다.
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public enum RefundOutcome {
        // 환불 성공으로 확정했다
        SUCCEEDED,
        // PG가 취소를 거절해 FAILED로 확정했다
        REJECTED,
        // PG 결제의 주문번호가 서버 주문과 달라 환불하지 않고 FAILED로 확정했다
        ORDER_MISMATCH,
        // 결과를 몰라 진행 중으로 남았다
        UNSETTLED;

        static RefundOutcome of(PaymentCancellationStatus status) {
            return switch (status) {
                case SUCCEEDED -> SUCCEEDED;
                case FAILED -> REJECTED;
                case REQUESTED, UNKNOWN -> UNSETTLED;
            };
        }
    }
}
