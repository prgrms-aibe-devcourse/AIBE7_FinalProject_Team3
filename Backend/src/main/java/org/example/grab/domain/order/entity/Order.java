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
import org.example.grab.domain.order.error.OrderErrorCode;
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

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    // 진행 중이거나 완료된 소비자 취소 요청의 Idempotency-Key. 결제 취소가 거절되면 비운다(ERD.md 3.3).
    @Column(name = "cancel_idempotency_key", length = 100)
    private String cancelIdempotencyKey;

    @Column(name = "cancel_request_hash", length = 64)
    private String cancelRequestHash;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

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

    public void expire(OffsetDateTime expiredAt) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.EXPIRED;
        this.expiredAt = Objects.requireNonNull(expiredAt);
    }

    // 소비자 취소 가능 상태(ERD.md 3.3). 배송이 시작됐거나 이미 끝난 주문은 취소하지 않는다.
    public boolean isCancelable() {
        return status == OrderStatus.PAYMENT_PENDING
                || status == OrderStatus.PAID
                || status == OrderStatus.PREPARING;
    }

    public boolean hasCancelRequest() {
        return cancelIdempotencyKey != null;
    }

    /**
     * 소비자 취소 요청을 기록한다. 결제 후 취소는 PG 결과를 받기 전까지 이 기록으로 다른 키의 취소 요청을 막는다.
     * 이미 다른 취소 요청이 진행 중이면 거부한다.
     */
    public void requestCancel(String idempotencyKey, String requestHash, String reason) {
        if (!isCancelable()) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        if (hasCancelRequest()) {
            throw new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }
        this.cancelIdempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.cancelRequestHash = Objects.requireNonNull(requestHash);
        this.cancelReason = Objects.requireNonNull(reason);
    }

    // 기록한 취소 요청대로 주문을 취소한다. 결제 후 취소는 PG 결제 취소 성공을 확인한 뒤에만 호출한다.
    public void cancel(OffsetDateTime canceledAt) {
        if (!isCancelable() || !hasCancelRequest()) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = Objects.requireNonNull(canceledAt);
    }

    // PG가 결제 취소를 거절했다. 주문은 그대로 두고 새 취소 요청을 받을 수 있게 기록을 비운다.
    public void clearCancelRequest() {
        if (status == OrderStatus.CANCELED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        this.cancelIdempotencyKey = null;
        this.cancelRequestHash = null;
        this.cancelReason = null;
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
