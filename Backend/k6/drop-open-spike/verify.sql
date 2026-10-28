-- PERF-002 검증: 스파이크 이후 상태 전환과 재고 정합성을 확인한다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/drop-open-spike/verify.sql

-- 1) WISH -> GRAB 전환이 됐는지 (안 됐다면 OPEN_DELAY_SECONDS 경과 전에 조회한 것).
SELECT id, status, sale_starts_at, grab_started_at
FROM drops WHERE name = 'k6 오픈스파이크 상품';

-- 2) 음수재고·초과판매 여부(0행 기대, stock-race/verify.sql과 동일 조건).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 오픈스파이크 상품'
  AND (
        o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity
        OR o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
      );

-- 3) 참고용: 실제로 몇 건 주문됐는지.
SELECT d.name AS drop_name, COUNT(oi.id) FILTER (WHERE ord.status <> 'CANCELED') AS created_orders
FROM drops d
JOIN order_items oi ON oi.drop_id = d.id
JOIN orders ord ON ord.id = oi.order_id
WHERE d.name = 'k6 오픈스파이크 상품'
GROUP BY d.name;
