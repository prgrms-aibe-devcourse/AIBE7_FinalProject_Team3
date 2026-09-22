package org.example.grab.domain.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.common.entity.BaseTimeEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        },
        indexes = {
                @Index(name = "idx_orders_buyer_created", columnList = "buyer_id, created_at, id"),
                @Index(name = "idx_orders_drop_status", columnList = "drop_id, status, id"),
                @Index(name = "idx_orders_payment_expiry", columnList = "status, payment_expires_at, id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 64, unique = true)
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

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 30)
    private String recipientPhone;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 300)
    private String addressLine1;

    @Column(name = "address_line2", length = 300)
    private String addressLine2;

    @Column(name = "delivery_memo", length = 300)
    private String deliveryMemo;

    @Column(name = "payment_expires_at", nullable = false)
    private OffsetDateTime paymentExpiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<OrderItem> items = new ArrayList<>();

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
            String recipientName,
            String recipientPhone,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String deliveryMemo,
            OffsetDateTime paymentExpiresAt
    ) {
        Order order = new Order();
        order.orderNumber = Objects.requireNonNull(orderNumber);
        order.buyerId = Objects.requireNonNull(buyerId);
        order.dropId = Objects.requireNonNull(dropId);
        order.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        order.requestHash = Objects.requireNonNull(requestHash);
        order.status = OrderStatus.PAYMENT_PENDING;
        order.productNameSnapshot = Objects.requireNonNull(productNameSnapshot);
        order.sellerNameSnapshot = Objects.requireNonNull(sellerNameSnapshot);
        order.itemsAmount = itemsAmount;
        order.shippingAmount = shippingAmount;
        order.totalAmount = Math.addExact(itemsAmount, shippingAmount);
        order.recipientName = Objects.requireNonNull(recipientName);
        order.recipientPhone = Objects.requireNonNull(recipientPhone);
        order.postalCode = Objects.requireNonNull(postalCode);
        order.addressLine1 = Objects.requireNonNull(addressLine1);
        order.addressLine2 = addressLine2;
        order.deliveryMemo = deliveryMemo;
        order.paymentExpiresAt = Objects.requireNonNull(paymentExpiresAt);
        return order;
    }

    public void addItem(OrderItem item) {
        Objects.requireNonNull(item);
        if (!dropId.equals(item.getDropId())) {
            throw new IllegalArgumentException("주문과 주문 항목의 DROP이 일치해야 합니다.");
        }
        item.assignOrder(this);
        items.add(item);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void cancel(OffsetDateTime canceledAt) {
        if (status != OrderStatus.PAYMENT_PENDING && status != OrderStatus.PAID) {
            throw new IllegalStateException("취소할 수 없는 주문 상태입니다.");
        }
        status = OrderStatus.CANCELED;
        this.canceledAt = Objects.requireNonNull(canceledAt);
    }

    public void prepareShipment() {
        if (status != OrderStatus.PAID) {
            throw new IllegalStateException("배송 준비로 변경할 수 없는 주문 상태입니다.");
        }
        status = OrderStatus.PREPARING;
    }

    public void ship() {
        if (status != OrderStatus.PREPARING) {
            throw new IllegalStateException("발송 처리할 수 없는 주문 상태입니다.");
        }
        status = OrderStatus.SHIPPED;
    }

    public void completeDelivery() {
        if (status != OrderStatus.SHIPPED) {
            throw new IllegalStateException("배송 완료로 변경할 수 없는 주문 상태입니다.");
        }
        status = OrderStatus.DELIVERED;
    }
}
