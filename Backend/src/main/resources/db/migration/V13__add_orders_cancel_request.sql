-- 소비자 주문 취소 요청을 주문에 기록한다(GR-24, ERD.md orders·3.3, ORDER.md 1.4).
-- 주문당 취소는 한 번이므로 취소 요청의 Idempotency-Key와 요청 해시를 주문에 두고, 키 범위는 주문별이다.
-- 결제 취소가 거절되면 키를 비워 새 요청을 허용하므로 유니크 제약은 두지 않는다.
-- 이미 CANCELED인 주문은 사유를 알 수 없어 고정 문구로 백필한 뒤 제약을 건다.

ALTER TABLE orders
    ADD COLUMN cancel_idempotency_key VARCHAR(100),
    ADD COLUMN cancel_request_hash VARCHAR(64),
    ADD COLUMN cancel_reason VARCHAR(500);

UPDATE orders
SET cancel_reason = '사유 미기록'
WHERE status = 'CANCELED' AND cancel_reason IS NULL;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_cancel_reason CHECK (status <> 'CANCELED' OR cancel_reason IS NOT NULL),
    ADD CONSTRAINT ck_orders_cancel_request CHECK (
        (cancel_idempotency_key IS NULL) = (cancel_request_hash IS NULL)
    );
