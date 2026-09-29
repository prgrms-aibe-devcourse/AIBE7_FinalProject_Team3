package org.example.grab.domain.shipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.global.entity.BaseEntity;

import java.time.OffsetDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(name = "shipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "carrier_code", nullable = false, length = 50)
    private String carrierCode;

    @Column(name = "tracking_number", nullable = false, length = 100)
    private String trackingNumber;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "shipped_at", nullable = false)
    private OffsetDateTime shippedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    private Shipment(
            Order order, String carrierCode, String trackingNumber,
            String idempotencyKey, String requestHash, OffsetDateTime shippedAt) {
        this.order = Objects.requireNonNull(order);
        this.carrierCode = Objects.requireNonNull(carrierCode);
        this.trackingNumber = Objects.requireNonNull(trackingNumber);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.shippedAt = Objects.requireNonNull(shippedAt);
    }

    public static Shipment register(
            Order order, String carrierCode, String trackingNumber,
            String idempotencyKey, String requestHash, OffsetDateTime shippedAt) {
        return new Shipment(order, carrierCode, trackingNumber, idempotencyKey, requestHash, shippedAt);
    }

    public void markDelivered(OffsetDateTime deliveredAt) {
        this.deliveredAt = Objects.requireNonNull(deliveredAt);
    }
}
