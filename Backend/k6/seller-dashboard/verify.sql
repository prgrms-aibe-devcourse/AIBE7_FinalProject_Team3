-- GR-173 검증: 읽기 전용 시나리오라 seed.sql이 만든 주문·위시·재고가 전혀 바뀌면 안 된다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/seller-dashboard/verify.sql

-- 기대값: 1000(DROP 200 x 주문 5).
SELECT COUNT(*) AS order_count
FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 판매자대시보드 상품 %';

-- 기대값: 4000(DROP 200 x 위시 20).
SELECT COUNT(*) AS wish_count
FROM wishes w JOIN drops d ON d.id = w.drop_id
WHERE d.name LIKE 'k6 판매자대시보드 상품 %';

-- seed.sql이 넣은 sold_quantity(50)·reserved_quantity(0)에서 벗어난 옵션이 있으면
-- 읽기 전용 시나리오가 실제로는 쓰기를 한 것이다(0행 기대).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity
FROM drop_options o JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 판매자대시보드 상품 %'
  AND (o.reserved_quantity <> 0 OR o.sold_quantity <> 50);
