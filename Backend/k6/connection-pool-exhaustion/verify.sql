-- GR-156 검증: 커넥션 풀이 바닥났더라도 재고 정합성은 깨지면 안 된다(초과판매·음수재고 0행 기대).
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/connection-pool-exhaustion/verify.sql

SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity,
       o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity AS remaining
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 커넥션풀 상품'
  AND (o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
       OR o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity);

-- 참고용: 실제 성공 주문 수(응답 201 수와 비교해 유실 여부 확인).
SELECT COUNT(*) AS created_orders
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 커넥션풀 상품';
