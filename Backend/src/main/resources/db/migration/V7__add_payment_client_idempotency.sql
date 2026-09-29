-- 결제 요청의 클라이언트 Idempotency-Key와 요청 해시를 저장한다(PAYMENT.md 1.1, COMMON.md 5절).
-- 기존 idempotency_key는 서버가 생성해 PG 승인 요청에 붙이는 UUID이므로 클라이언트 키와 분리한다.
-- 클라이언트 키 범위는 주문별이며, 같은 키에 다른 요청 본문이 오면 request_hash로 탐지해 409로 거부한다.
--
-- 결제 기능 도입 전이라 기존 행은 없어야 하지만, 남아 있어도 마이그레이션이 실패하지 않도록
-- 컬럼을 NULL 허용으로 추가하고 서버 키로 백필한 뒤 NOT NULL을 건다.
-- 백필한 request_hash는 실제 요청 해시가 아니므로 해당 행의 키로 재요청하면 다른 요청으로 판단된다.

ALTER TABLE payments
    ADD COLUMN client_idempotency_key VARCHAR(100),
    ADD COLUMN request_hash VARCHAR(64);

UPDATE payments
SET client_idempotency_key = idempotency_key,
    request_hash = repeat('0', 64)
WHERE client_idempotency_key IS NULL;

ALTER TABLE payments
    ALTER COLUMN client_idempotency_key SET NOT NULL,
    ALTER COLUMN request_hash SET NOT NULL,
    ADD CONSTRAINT uq_payments_order_client_idempotency UNIQUE (order_id, client_idempotency_key);
