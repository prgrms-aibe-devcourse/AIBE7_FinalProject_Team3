-- STOCK-001/NFR-003 검증: 음수 재고·초과 판매가 없어야 한다(0행 기대).
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/01-stock-race/verify.sql
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 재고경쟁 상품'
  AND (
        o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity
        OR o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
      );

-- 참고용: 실제로 몇 건 성공했는지.
SELECT d.name AS drop_name, o.total_quantity,
       COUNT(oi.id) FILTER (WHERE ord.status <> 'CANCELED') AS created_orders
FROM drops d
JOIN drop_options o ON o.drop_id = d.id
LEFT JOIN order_items oi ON oi.option_id = o.id
LEFT JOIN orders ord ON ord.id = oi.order_id
WHERE d.name = 'k6 재고경쟁 상품'
GROUP BY d.name, o.total_quantity;
