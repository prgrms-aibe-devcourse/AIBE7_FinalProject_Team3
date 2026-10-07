package org.example.grab.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.UUIDEntity;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

// 결제 한 건에 대한 PG 취소 요청. PG 호출 전에 REQUESTED로 저장해, 호출 도중 서버가 멈춰도 요청이 남도록 한다(ERD.md 3.3).
@Getter
@Entity
@Table(
        name = "payment_cancellations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payment_cancellations_idempotency", columnNames = "idempotency_key")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancellation extends UUIDEntity {

    // 결과 불명 취소를 정리 작업(GR-65)이 다시 요청할 때까지 기다리는 시간
    static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    private static final int FAILURE_CODE_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // 소비자 취소면 요청한 회원, 시스템 보정이면 null
    @Column(name = "requested_by")
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentCancellationPurpose purpose;

    // PG 취소 요청의 Idempotency-Key. 클라이언트 키를 PG로 흘려보내지 않도록 서버가 새로 만든다(COMMON.md 5절).
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentCancellationStatus status;

    // 토스페이먼츠 취소 거래 키(transactionKey)
    @Column(name = "provider_cancel_id", length = 200)
    private String providerCancelId;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failure_code", length = FAILURE_CODE_MAX_LENGTH)
    private String failureCode;

    // PG에 취소를 요청한 횟수
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    private PaymentCancellation(
            Long paymentId, Long requestedBy, PaymentCancellationPurpose purpose, long amount, String reason) {
        if (amount < 0) {
            throw new IllegalArgumentException("취소 금액은 0 이상이어야 합니다.");
        }
        this.paymentId = Objects.requireNonNull(paymentId);
        this.requestedBy = requestedBy;
        this.purpose = Objects.requireNonNull(purpose);
        this.idempotencyKey = UUID.randomUUID().toString();
        this.amount = amount;
        this.reason = Objects.requireNonNull(reason);
        this.status = PaymentCancellationStatus.REQUESTED;
    }

    // 소비자 주문 취소로 결제를 전액 취소한다. MVP는 전액 취소만 지원한다.
    public static PaymentCancellation requestOrderCancel(Long paymentId, Long buyerId, long amount, String reason) {
        return new PaymentCancellation(
                paymentId, Objects.requireNonNull(buyerId), PaymentCancellationPurpose.ORDER_CANCEL, amount, reason);
    }

    // PG 응답을 기다리거나 결과를 알 수 없는 요청. 이 요청이 끝날 때까지 주문의 배송 처리와 다른 취소 요청을 막는다.
    public boolean isInProgress() {
        return status == PaymentCancellationStatus.REQUESTED || status == PaymentCancellationStatus.UNKNOWN;
    }

    public void succeed(String providerCancelId, OffsetDateTime completedAt) {
        recordAttempt();
        this.status = PaymentCancellationStatus.SUCCEEDED;
        this.providerCancelId = providerCancelId;
        this.completedAt = Objects.requireNonNull(completedAt);
        this.nextRetryAt = null;
    }

    public void fail(String failureCode, OffsetDateTime completedAt) {
        recordAttempt();
        this.status = PaymentCancellationStatus.FAILED;
        this.failureCode = truncate(failureCode);
        this.completedAt = Objects.requireNonNull(completedAt);
        this.nextRetryAt = null;
    }

    public void markUnknown(String failureCode, OffsetDateTime now) {
        recordAttempt();
        this.status = PaymentCancellationStatus.UNKNOWN;
        this.failureCode = truncate(failureCode);
        this.nextRetryAt = now.plus(RETRY_AFTER);
    }

    private void recordAttempt() {
        if (!isInProgress()) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.attemptCount++;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= FAILURE_CODE_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, FAILURE_CODE_MAX_LENGTH);
    }
}
