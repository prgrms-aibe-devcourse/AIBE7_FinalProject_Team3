-- idx_orders_payment_expiry를 부분 인덱스로 전환.
--
-- 결제 대기 만료 배치(OrderRepository의 findOrdersForExpiry류)는 항상
-- status = 'PAYMENT_PENDING'으로 고정해서만 조회한다. PAYMENT_PENDING은 주문 생성 직후부터
-- 결제 완료/만료 전까지의 짧은 대기 상태라 전체 주문 중 비중이 작은데, 기존 인덱스는
-- status를 선두 컬럼으로 넣어뒀을 뿐 DELIVERED/CANCELED 등 나머지 모든 상태의 행까지
-- 통째로 인덱싱하고 있었다. WHERE 절로 좁혀서 실제 조회 대상만 인덱싱한다.
-- idx_payments_in_progress(WHERE status IN ('PENDING','UNKNOWN'))와 같은 패턴이다.
DROP INDEX idx_orders_payment_expiry;

CREATE INDEX idx_orders_payment_expiry
    ON orders (payment_expires_at, id)
    WHERE status = 'PAYMENT_PENDING';
