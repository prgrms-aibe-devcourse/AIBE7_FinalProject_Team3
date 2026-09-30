package org.example.grab.domain.payment.gateway;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * PG 승인·조회 결과.
 *
 * @param pgStatus    PG가 돌려준 결제 상태 원문(예: DONE, ABORTED). 오류 응답·통신 실패면 null
 * @param orderId     PG가 돌려준 주문 식별자. 서버 주문번호와 비교하는 데 쓴다
 * @param totalAmount PG가 돌려준 결제 금액. 서버 주문 금액과 비교하는 데 쓴다
 * @param awaitingConfirmation 사용자 인증은 끝났지만 PG가 승인 요청을 받지 않은 상태. UNKNOWN일 때만 true일 수 있다
 */
public record PaymentGatewayResult(
        Outcome outcome,
        String pgStatus,
        String orderId,
        Long totalAmount,
        OffsetDateTime approvedAt,
        String failureCode,
        String failureMessage,
        boolean awaitingConfirmation
) {

    public enum Outcome {
        // PG가 승인을 완료했다.
        APPROVED,
        // PG가 승인하지 않았음이 확실하다. 재시도를 허용해도 이중 결제가 생기지 않는다.
        NOT_APPROVED,
        // 승인 여부를 알 수 없다. 실패로 단정하지 않는다.
        UNKNOWN
    }

    public PaymentGatewayResult {
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.APPROVED) {
            Objects.requireNonNull(approvedAt);
        }
        if (awaitingConfirmation && outcome != Outcome.UNKNOWN) {
            throw new IllegalArgumentException("승인 대기는 결과 불명에서만 표시한다.");
        }
    }

    public static PaymentGatewayResult approved(
            String pgStatus, String orderId, Long totalAmount, OffsetDateTime approvedAt) {
        return new PaymentGatewayResult(
                Outcome.APPROVED, pgStatus, orderId, totalAmount, approvedAt, null, null, false);
    }

    public static PaymentGatewayResult notApproved(String pgStatus, String failureCode, String failureMessage) {
        return new PaymentGatewayResult(
                Outcome.NOT_APPROVED, pgStatus, null, null, null, failureCode, failureMessage, false);
    }

    public static PaymentGatewayResult unknown(String pgStatus, String failureCode, String failureMessage) {
        return new PaymentGatewayResult(
                Outcome.UNKNOWN, pgStatus, null, null, null, failureCode, failureMessage, false);
    }

    // 승인 요청이 PG에 닿지 않았을 수 있다. 같은 서버 멱등 키로 다시 승인을 요청해도 중복 승인되지 않는다.
    public static PaymentGatewayResult awaitingConfirmation(String pgStatus) {
        return new PaymentGatewayResult(
                Outcome.UNKNOWN, pgStatus, null, null, null, null, "승인 요청 전 상태입니다.", true);
    }
}
