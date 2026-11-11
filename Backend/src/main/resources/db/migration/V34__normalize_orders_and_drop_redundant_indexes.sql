-- orders 테이블 정규화 및 불필요 인덱스 정리
-- 1. 배송지 정보를 order_shipping_addresses 테이블로 분리 (orders 6개 컬럼 제거)
-- 2. 소비자 취소 정보를 order_cancellations 테이블로 분리 (orders 4개 컬럼 제거)
-- 3. uq_orders_order_number 제약조건(인덱스) 제거 (컬럼은 유지, 조회 미사용 및 난수 생성)
-- 4. idx_orders_drop_created 인덱스 제거 (idx_orders_seller_created로 통합)

-- 1. order_shipping_addresses 테이블 생성 및 데이터 이관
CREATE TABLE order_shipping_addresses (
    order_id BIGINT NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(30) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    address_line1 VARCHAR(300) NOT NULL,
    address_line2 VARCHAR(300),
    delivery_memo VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_order_shipping_addresses PRIMARY KEY (order_id),
    CONSTRAINT fk_order_shipping_addresses_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE
);

INSERT INTO order_shipping_addresses (
    order_id, recipient_name, recipient_phone, postal_code, address_line1, address_line2, delivery_memo, created_at
)
SELECT id, recipient_name, recipient_phone, postal_code, address_line1, address_line2, delivery_memo, created_at
FROM orders;

-- 2. order_cancellations 테이블 생성 및 데이터 이관
CREATE TABLE order_cancellations (
    order_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    canceled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_order_cancellations PRIMARY KEY (order_id),
    CONSTRAINT fk_order_cancellations_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE
);

INSERT INTO order_cancellations (
    order_id, idempotency_key, request_hash, reason, canceled_at, created_at
)
SELECT id, cancel_idempotency_key, cancel_request_hash, cancel_reason, canceled_at, canceled_at
FROM orders
WHERE cancel_idempotency_key IS NOT NULL;

-- 3. orders 테이블의 인덱스 및 제약조건 정리
DROP INDEX idx_orders_drop_created;

ALTER TABLE orders
    DROP CONSTRAINT uq_orders_order_number,
    DROP CONSTRAINT ck_orders_canceled_at,
    DROP CONSTRAINT ck_orders_paid_at,
    DROP CONSTRAINT ck_orders_expired_at;

-- 4. orders 테이블에서 분리/정리된 컬럼 삭제
ALTER TABLE orders
    DROP COLUMN recipient_name,
    DROP COLUMN recipient_phone,
    DROP COLUMN postal_code,
    DROP COLUMN address_line1,
    DROP COLUMN address_line2,
    DROP COLUMN delivery_memo,
    DROP COLUMN cancel_idempotency_key,
    DROP COLUMN cancel_request_hash,
    DROP COLUMN cancel_reason,
    DROP COLUMN canceled_at,
    DROP COLUMN paid_at,
    DROP COLUMN expired_at;
