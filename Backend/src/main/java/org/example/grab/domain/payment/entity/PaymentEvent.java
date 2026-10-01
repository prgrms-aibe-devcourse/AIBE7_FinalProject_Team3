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
import org.example.grab.global.entity.BaseEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Objects;

// PG와 주고받은 결제 결과 한 건의 기록. (payment_id, event_key) 유일 제약으로 같은 결과를 두 번 반영하지 않는다.
@Getter
@Entity
@Table(
        name = "payment_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_payment_events_key", columnNames = {"payment_id", "event_key"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private PaymentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentEventSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_result", nullable = false, length = 30)
    private PaymentEventResult processingResult;

    // 민감정보를 제거한 허용 필드만 담은 JSON(ERD.md 1.5). PG 원본 응답을 그대로 저장하지 않는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    private PaymentEvent(
            Long paymentId,
            String eventKey,
            PaymentEventType eventType,
            PaymentEventSource source,
            PaymentEventResult processingResult,
            String payload,
            OffsetDateTime occurredAt
    ) {
        this.paymentId = Objects.requireNonNull(paymentId);
        this.eventKey = Objects.requireNonNull(eventKey);
        this.eventType = Objects.requireNonNull(eventType);
        this.source = Objects.requireNonNull(source);
        this.processingResult = Objects.requireNonNull(processingResult);
        this.payload = Objects.requireNonNull(payload);
        this.occurredAt = occurredAt;
    }

    public static PaymentEvent record(
            Long paymentId,
            String eventKey,
            PaymentEventType eventType,
            PaymentEventSource source,
            PaymentEventResult processingResult,
            String payload,
            OffsetDateTime occurredAt
    ) {
        return new PaymentEvent(paymentId, eventKey, eventType, source, processingResult, payload, occurredAt);
    }
}
