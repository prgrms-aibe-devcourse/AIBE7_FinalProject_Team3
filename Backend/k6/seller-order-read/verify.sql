-- GR-174 검증: 목록/상세 조회는 읽기 전용이지만, order_burst 시나리오가 실제 주문
-- API로 새 주문을 계속 추가하므로 seed.sql이 깐 1000건보다 늘어나 있는 게 정상이다.
-- 여기서는 재고 카운터가 범위를 벗어나지 않았는지, 고아 데이터가 없는지만 본다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/seller-order-read/verify.sql

-- 참고용: 최종 주문 수(seed.sql 1000건 + order_burst가 추가한 건수).
SELECT COUNT(*) AS order_count
FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 판매자주문조회 상품';

-- 재고 카운터가 범위를 벗어나면 0행이 아니다(초과 예약·음수 재고 등).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 판매자주문조회 상품'
  AND (o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
       OR o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity);

-- order_burst가 만든 주문(PAYMENT_PENDING)이 품목·예약 없이 떠 있으면 고아 데이터다(0행 기대).
SELECT o.id AS order_id
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 판매자주문조회 상품'
  AND o.status = 'PAYMENT_PENDING'
  AND NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id);
