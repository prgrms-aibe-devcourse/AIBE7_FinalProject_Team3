-- 인덱스 전수 감사(2026-11-06~07)에서 확인된 중복 인덱스 제거.
--
-- idx_orders_drop_status (drop_id, status, id): 드롭 안에서 상태로 거르는 쿼리가 없다.
-- idx_orders_drop_created (drop_id, created_at, id)와 idx_orders_status_id (status, id)를
-- BitmapAnd로 결합해도 같은 조건을 동일한 비용으로 서빙한다(EXPLAIN ANALYZE로 확인, 차이 없음).
DROP INDEX idx_orders_drop_status;

-- idx_orders_status_id (status, id): V31에서 배송 배치의 status='SHIPPED' 필터용으로
-- 추가했으나, 이미 있던 idx_orders_payment_expiry (status, payment_expires_at, id)가
-- 선두 컬럼이 status라 같은 필터를 그대로 받아준다(EXPLAIN ANALYZE: 0.914ms, 전용 인덱스와
-- 차이 없음). V31은 이미 병합돼 있어 수정하지 않고 여기서 드롭한다.
DROP INDEX idx_orders_status_id;

-- idx_payments_order_created (order_id, created_at, id): PaymentRepository의 실제 쿼리는
-- findByOrderIdOrderByIdAsc / findByOrderIdAndStatusInOrderByIdAsc로 전부 id 정렬이며,
-- created_at으로 정렬하는 쿼리는 없다. order_id 단건 조회는 uq_payments_order_client_idempotency
-- (order_id, client_idempotency_key)가 이미 선두 컬럼으로 커버하고, 주문당 결제 행도 1~3건뿐이라
-- 전용 인덱스가 불필요하다.
DROP INDEX idx_payments_order_created;
