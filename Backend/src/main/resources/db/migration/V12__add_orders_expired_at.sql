-- 결제 대기 만료 시각을 남긴다(GR-22, ERD.md orders). 상태 이력 테이블이 없으므로 전환 시각은 컬럼으로 보존한다.
-- 이미 EXPIRED인 주문은 만료 시각을 알 수 없어 마지막 변경 시각으로 백필한 뒤 제약을 건다.

ALTER TABLE orders
    ADD COLUMN expired_at TIMESTAMPTZ;

UPDATE orders
SET expired_at = updated_at
WHERE status = 'EXPIRED' AND expired_at IS NULL;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_expired_at CHECK (status <> 'EXPIRED' OR expired_at IS NOT NULL);
