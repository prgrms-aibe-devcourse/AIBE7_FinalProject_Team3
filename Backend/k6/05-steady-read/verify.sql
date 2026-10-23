-- PERF-003 검증: 읽기 전용 시나리오라 주문·재고가 전혀 생기면 안 된다(둘 다 0행 기대).
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/05-steady-read/verify.sql

SELECT COUNT(*) AS unexpected_orders
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 평상시조회 상품 %';

SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 평상시조회 상품 %'
  AND (o.reserved_quantity <> 0 OR o.sold_quantity <> 0);
