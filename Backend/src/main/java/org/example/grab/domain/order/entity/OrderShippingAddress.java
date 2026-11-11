package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
@Table(name = "order_shipping_addresses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderShippingAddress {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "order_id")
    private Order order;

    @Embedded
    private ShippingAddress address;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public OrderShippingAddress(Order order, ShippingAddress address, OffsetDateTime createdAt) {
        this.order = Objects.requireNonNull(order);
        this.address = Objects.requireNonNull(address);
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
