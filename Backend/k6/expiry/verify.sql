-- STOCK-005 검증: 만료 배치와 취소 요청이 경합해도 재고가 한 번만 반환돼야 한다.
-- order_item 1건당 stock_reservations 행도 1건이어야 하고(released_at, release_reason 둘 다
-- 채워짐), release_reason은 'EXPIRED' 또는 'ORDER_CANCELED' 중 하나만 찍혀야 한다(중복 반환 없음).
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/expiry/verify.sql
SELECT o.order_number, o.status, oi.option_id, sr.status AS reservation_status,
       sr.release_reason, sr.release_destination, sr.released_at
FROM orders o
JOIN drops d ON d.id = o.drop_id
JOIN order_items oi ON oi.order_id = o.id
JOIN stock_reservations sr ON sr.order_item_id = oi.id
WHERE d.name = 'k6 만료취소 상품'
ORDER BY o.created_at;

-- 재고 자체도 음수·초과 판매가 없는지 같이 확인한다(0행 기대).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 만료취소 상품'
  AND (
        o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity
        OR o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
      );
