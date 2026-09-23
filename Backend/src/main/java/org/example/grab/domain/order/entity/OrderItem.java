package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.BaseEntity;

import java.util.Objects;

@Getter
@Entity
@Table(
        name = "order_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_order_items_order_option",
                columnNames = {"order_id", "option_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "drop_id", nullable = false)
    private Long dropId;

    @Column(name = "option_id", nullable = false)
    private Long optionId;

    @Column(name = "option_name_snapshot", nullable = false, length = 200)
    private String optionNameSnapshot;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    private OrderItem(
            Order order,
            Long optionId,
            String optionNameSnapshot,
            long unitPrice,
            int quantity
    ) {
        if (unitPrice < 0) {
            throw new IllegalArgumentException("옵션 단가는 0 이상이어야 합니다.");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다.");
        }
        this.order = Objects.requireNonNull(order);
        this.dropId = order.getDropId();
        this.optionId = Objects.requireNonNull(optionId);
        this.optionNameSnapshot = Objects.requireNonNull(optionNameSnapshot);
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public static OrderItem create(
            Order order,
            Long optionId,
            String optionNameSnapshot,
            long unitPrice,
            int quantity
    ) {
        return new OrderItem(order, optionId, optionNameSnapshot, unitPrice, quantity);
    }
}
