package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.common.entity.BaseTimeEntity;

import java.time.OffsetDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(
        name = "shipments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_shipments_order",
                columnNames = "order_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "carrier_code", nullable = false, length = 50)
    private String carrierCode;

    @Column(name = "tracking_number", nullable = false, length = 100)
    private String trackingNumber;

    @Column(name = "shipped_at")
    private OffsetDateTime shippedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    public static Shipment create(
            Order order,
            String idempotencyKey,
            String requestHash,
            String carrierCode,
            String trackingNumber,
            OffsetDateTime shippedAt
    ) {
        Shipment shipment = new Shipment();
        shipment.order = Objects.requireNonNull(order);
        shipment.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        shipment.requestHash = Objects.requireNonNull(requestHash);
        shipment.carrierCode = Objects.requireNonNull(carrierCode);
        shipment.trackingNumber = Objects.requireNonNull(trackingNumber);
        shipment.shippedAt = Objects.requireNonNull(shippedAt);
        return shipment;
    }

    public void completeDelivery(OffsetDateTime deliveredAt) {
        OffsetDateTime completedAt = Objects.requireNonNull(deliveredAt);
        if (completedAt.isBefore(shippedAt)) {
            throw new IllegalArgumentException("배송 완료 시각은 출고 시각보다 빠를 수 없습니다.");
        }
        this.deliveredAt = completedAt;
    }
}
