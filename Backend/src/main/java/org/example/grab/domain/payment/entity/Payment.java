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
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.global.entity.UUIDEntity;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

// 주문 하나에 대한 결제 시도 한 건. 주문은 order 도메인 소유이므로 연관관계 대신 order_id만 보관한다.
@Getter
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_payments_idempotency", columnNames = {"provider", "idempotency_key"}),
                @UniqueConstraint(
                        name = "uq_payments_order_client_idempotency",
                        columnNames = {"order_id", "client_idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uq_payments_provider_payment",
                        columnNames = {"provider", "provider_payment_id"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends UUIDEntity {

    private static final int FAILURE_CODE_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    // PG 승인 요청의 Idempotency-Key. 클라이언트 키를 PG로 흘려보내지 않도록 서버가 새로 만든다(COMMON.md 5절).
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "client_idempotency_key", nullable = false, length = 100)
    private String clientIdempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    // 토스페이먼츠 paymentKey
    @Column(name = "provider_payment_id", length = 200)
    private String providerPaymentId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "reconciliation_reason", columnDefinition = "text")
    private String reconciliationReason;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "failure_code", length = FAILURE_CODE_MAX_LENGTH)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    private Payment(
            Long orderId,
            PaymentProvider provider,
            String clientIdempotencyKey,
            String requestHash,
            String providerPaymentId,
            long amount
    ) {
        if (amount < 0) {
            throw new IllegalArgumentException("결제 금액은 0 이상이어야 합니다.");
        }
        this.orderId = Objects.requireNonNull(orderId);
        this.provider = Objects.requireNonNull(provider);
        this.idempotencyKey = UUID.randomUUID().toString();
        this.clientIdempotencyKey = Objects.requireNonNull(clientIdempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.providerPaymentId = Objects.requireNonNull(providerPaymentId);
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.reconciliationStatus = ReconciliationStatus.NONE;
    }

    // PG 승인 요청 전에 PENDING으로 저장해, 승인 도중 서버가 멈춰도 결제 시도가 남도록 한다.
    public static Payment request(
            Long orderId,
            PaymentProvider provider,
            String clientIdempotencyKey,
            String requestHash,
            String providerPaymentId,
            long amount
    ) {
        return new Payment(orderId, provider, clientIdempotencyKey, requestHash, providerPaymentId, amount);
    }

    // 결과가 확정되지 않은 시도. 같은 주문의 새 결제를 막아 이중 결제를 방지한다.
    public boolean isInProgress() {
        return status == PaymentStatus.PENDING || status == PaymentStatus.UNKNOWN;
    }

    public void succeed(OffsetDateTime approvedAt) {
        resolve(PaymentStatus.SUCCEEDED);
        this.approvedAt = Objects.requireNonNull(approvedAt);
    }

    // PG는 승인했지만 주문을 확정할 수 없는 경우(결제 마감 후 승인, 금액 불일치). 환불 등 보정이 필요하다.
    public void succeedRequiringReconciliation(OffsetDateTime approvedAt, String reason) {
        requireInProgress();
        this.status = PaymentStatus.SUCCEEDED;
        this.approvedAt = Objects.requireNonNull(approvedAt);
        requireReconciliation(reason);
    }

    public void fail(String failureCode, String failureMessage) {
        resolve(PaymentStatus.FAILED);
        this.failureCode = truncate(failureCode, FAILURE_CODE_MAX_LENGTH);
        this.failureMessage = failureMessage;
    }

    // 승인·조회 모두 결과를 확인하지 못한 경우. 실패로 단정하지 않고 보정 대상으로 남긴다(ERD.md 3.2).
    public void markUnknown(String reason) {
        requireInProgress();
        this.status = PaymentStatus.UNKNOWN;
        requireReconciliation(reason);
    }

    // UNKNOWN을 PG 조회로 확정하면 보정이 끝난 것이므로 RESOLVED로 바꾼다.
    private void resolve(PaymentStatus resolvedStatus) {
        requireInProgress();
        if (status == PaymentStatus.UNKNOWN) {
            this.reconciliationStatus = ReconciliationStatus.RESOLVED;
        }
        this.status = resolvedStatus;
    }

    private void requireInProgress() {
        if (!isInProgress()) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private void requireReconciliation(String reason) {
        this.reconciliationStatus = ReconciliationStatus.REQUIRED;
        this.reconciliationReason = Objects.requireNonNull(reason);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
