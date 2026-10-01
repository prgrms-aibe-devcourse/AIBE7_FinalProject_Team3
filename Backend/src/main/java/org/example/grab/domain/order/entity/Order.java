package org.example.grab.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

import java.time.OffsetDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_orders_order_number", columnNames = "order_number"),
                @UniqueConstraint(
                        name = "uq_orders_buyer_idempotency",
                        columnNames = {"buyer_id", "idempotency_key"}
                ),
                @UniqueConstraint(name = "uq_orders_id_drop", columnNames = {"id", "drop_id"})
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends UUIDEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "drop_id", nullable = false)
    private Long dropId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "product_name_snapshot", nullable = false, length = 200)
    private String productNameSnapshot;

    @Column(name = "seller_name_snapshot", nullable = false, length = 100)
    private String sellerNameSnapshot;

    @Column(name = "items_amount", nullable = false)
    private long itemsAmount;

    @Column(name = "shipping_amount", nullable = false)
    private long shippingAmount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(name = "payment_expires_at", nullable = false)
    private OffsetDateTime paymentExpiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    private Order(
            String orderNumber,
            Long buyerId,
            Long dropId,
            String idempotencyKey,
            String requestHash,
            String productNameSnapshot,
            String sellerNameSnapshot,
            long itemsAmount,
            long shippingAmount,
            ShippingAddress shippingAddress,
            OffsetDateTime paymentExpiresAt
    ) {
        if (itemsAmount < 0 || shippingAmount < 0) {
            throw new IllegalArgumentException("주문 금액은 0 이상이어야 합니다.");
        }
        this.orderNumber = Objects.requireNonNull(orderNumber);
        this.buyerId = Objects.requireNonNull(buyerId);
        this.dropId = Objects.requireNonNull(dropId);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.status = OrderStatus.PAYMENT_PENDING;
        this.productNameSnapshot = Objects.requireNonNull(productNameSnapshot);
        this.sellerNameSnapshot = Objects.requireNonNull(sellerNameSnapshot);
        this.itemsAmount = itemsAmount;
        this.shippingAmount = shippingAmount;
        this.totalAmount = Math.addExact(itemsAmount, shippingAmount);
        this.shippingAddress = Objects.requireNonNull(shippingAddress);
        this.paymentExpiresAt = Objects.requireNonNull(paymentExpiresAt);
    }

    public static Order create(
            String orderNumber,
            Long buyerId,
            Long dropId,
            String idempotencyKey,
            String requestHash,
            String productNameSnapshot,
            String sellerNameSnapshot,
            long itemsAmount,
            long shippingAmount,
            ShippingAddress shippingAddress,
            OffsetDateTime paymentExpiresAt
    ) {
        return new Order(
                orderNumber,
                buyerId,
                dropId,
                idempotencyKey,
                requestHash,
                productNameSnapshot,
                sellerNameSnapshot,
                itemsAmount,
                shippingAmount,
                shippingAddress,
                paymentExpiresAt
        );
    }

    // 결제 마감 시각 정각부터 만료로 본다. 결제 확정과 만료 처리가 같은 기준을 써야 두 경로가 동시에 성공하지 않는다.
    // 결제 요청 검증(PayableOrder)도 이 메서드를 쓴다. 기준을 바꿀 때는 여기만 고친다.
    public static boolean isPaymentExpired(OffsetDateTime paymentExpiresAt, OffsetDateTime now) {
        return !now.isBefore(paymentExpiresAt);
    }

    public boolean isPaymentExpired(OffsetDateTime now) {
        return isPaymentExpired(paymentExpiresAt, now);
    }

    public void markPaid(OffsetDateTime paidAt) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.PAID;
        this.paidAt = Objects.requireNonNull(paidAt);
    }

    public void expire() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.EXPIRED;
    }

    public void prepareShipment() {
        if (status != OrderStatus.PAID) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.PREPARING;
    }

    public void ship() {
        if (status != OrderStatus.PREPARING) {
            throw new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }
        this.status = OrderStatus.SHIPPED;
    }

    public void completeDelivery() {
        if (status != OrderStatus.SHIPPED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.DELIVERED;
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", uuid=" + getUuid() +
                ", orderNumber='" + orderNumber + '\'' +
                ", status=" + status +
                '}';
    }
}
