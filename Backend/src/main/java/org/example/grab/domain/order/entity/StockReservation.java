package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.example.grab.global.entity.BaseEntity;

import java.time.OffsetDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(name = "stock_reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, unique = true)
    private OrderItem orderItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 30)
    private ReleaseReason releaseReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_destination", length = 20)
    private ReleaseDestination releaseDestination;

    private StockReservation(OrderItem orderItem, OffsetDateTime expiresAt) {
        this.orderItem = Objects.requireNonNull(orderItem);
        this.status = ReservationStatus.HELD;
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public static StockReservation hold(OrderItem orderItem, OffsetDateTime expiresAt) {
        return new StockReservation(orderItem, expiresAt);
    }
}
