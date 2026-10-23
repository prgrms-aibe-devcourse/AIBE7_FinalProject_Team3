-- 시나리오 실행 후 공통 검증. 전부 결과가 0행(또는 명시한 조건)이어야 정상이다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/verify.sql

-- 1) STOCK-001/NFR-003: 음수 재고·초과 판매가 없어야 한다(0행 기대).
SELECT id AS option_id, total_quantity, reserved_quantity, sold_quantity, withheld_quantity
FROM drop_options
WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 부하테스트 상품')
  AND (
        reserved_quantity + sold_quantity + withheld_quantity > total_quantity
        OR reserved_quantity < 0 OR sold_quantity < 0 OR withheld_quantity < 0
      );

-- 2) 멱등성: 같은 (buyer, idempotency_key)로 주문이 2건 이상 생기면 안 된다(0행 기대).
--    orders.uq_orders_buyer_idempotency 유니크 제약으로 이미 DB 레벨에서 막히지만,
--    애플리케이션 로직이 의도대로 동작했는지 다시 한번 확인한다.
SELECT buyer_id, idempotency_key, COUNT(*)
FROM orders
WHERE buyer_id = (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local')
GROUP BY buyer_id, idempotency_key
HAVING COUNT(*) > 1;

-- 3) 참고용: 현재까지 생성된 k6 테스트 주문 전체 상태 확인.
SELECT o.order_number, o.status, o.idempotency_key, o.payment_expires_at, o.canceled_at
FROM orders o
WHERE o.buyer_id = (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local')
ORDER BY o.created_at;

-- 4) STOCK-005: expiry-cancel 시나리오 주문의 재고 반환 기록 — order_item 1건당
--    stock_reservations 행도 1건이어야 하고(released_at, release_reason 둘 다 채워짐),
--    release_reason은 'EXPIRED' 또는 'ORDER_CANCELED' 중 하나만 찍혀야 한다(중복 반환 없음).
SELECT o.order_number, oi.option_id, sr.status, sr.release_reason, sr.release_destination, sr.released_at
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
JOIN stock_reservations sr ON sr.order_item_id = oi.id
WHERE o.buyer_id = (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local')
ORDER BY o.created_at;
