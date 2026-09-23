package org.example.grab.domain.order.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.example.grab.global.entity.UUIDEntity;

import java.time.OffsetDateTime;

// 주문 테이블의 영속 필드를 매핑한다.
@Entity
@Table(name = "orders")
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

    @Column(nullable = false, length = 30)
    private String status;

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
}
