package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(name = "order_cancellations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCancellation {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public OrderCancellation(
            Order order,
            String idempotencyKey,
            String requestHash,
            String reason,
            OffsetDateTime canceledAt,
            OffsetDateTime createdAt
    ) {
        this.order = Objects.requireNonNull(order);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.reason = Objects.requireNonNull(reason);
        this.canceledAt = canceledAt;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public void markCanceled(OffsetDateTime canceledAt) {
        this.canceledAt = Objects.requireNonNull(canceledAt);
    }
}
