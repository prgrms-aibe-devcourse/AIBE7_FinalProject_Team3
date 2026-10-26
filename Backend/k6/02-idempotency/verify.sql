-- 멱등성 검증: 같은 (buyer, idempotency_key)로 주문이 2건 이상 생기면 안 된다(0행 기대).
-- orders.uq_orders_buyer_idempotency 유니크 제약으로 이미 DB 레벨에서 막히지만,
-- 애플리케이션 로직(OrderIdempotencyService)이 의도대로 동작했는지 다시 한번 확인한다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/02-idempotency/verify.sql
SELECT o.buyer_id, o.idempotency_key, COUNT(*)
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 멱등성 상품'
GROUP BY o.buyer_id, o.idempotency_key
HAVING COUNT(*) > 1;

-- 참고용: concurrentSameBody(동시 동일요청)가 실제로 주문 1건만 만들었는지,
-- sameKeyDifferentBody(같은 키 다른 본문)가 최초 요청 1건만 남겼는지 눈으로 확인.
SELECT o.order_number, o.status, o.idempotency_key
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 멱등성 상품'
ORDER BY o.created_at;
