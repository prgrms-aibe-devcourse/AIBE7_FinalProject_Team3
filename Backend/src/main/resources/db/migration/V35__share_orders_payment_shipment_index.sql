-- 결제 만료용 부분 인덱스를 재사용해 배송 배치의 SHIPPED 상태 조회도 지원한다.
DROP INDEX idx_orders_payment_expiry;

CREATE INDEX idx_orders_payment_expiry
    ON orders (status, payment_expires_at, id)
    WHERE status IN ('PAYMENT_PENDING', 'SHIPPED');
