-- 커넥션 풀 고갈 시나리오 전용 DROP/옵션(GR-156). 재고를 넉넉히 둬서 주문이
-- INSUFFICIENT_STOCK 없이 거의 다 성공하게 해, 재고 부족이 아니라 순수하게
-- 동시 DB 커넥션 수요(주문 쓰기 + 목록/상세 읽기)로 풀이 바닥나는지만 본다.
-- 먼저 k6/common/accounts-seed.sql을 실행해 계정을 만들어둔 상태여야 한다.
-- 재실행 가능하도록 이 시나리오가 만든 주문부터 먼저 지운다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/connection-pool-exhaustion/seed.sql
BEGIN;

DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name = 'k6 커넥션풀 상품'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 커넥션풀 상품');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 커넥션풀 상품');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 커넥션풀 상품');
DELETE FROM drops WHERE name = 'k6 커넥션풀 상품';

WITH drop_row AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 커넥션풀 상품', 'GR-156 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 day', now() + interval '1 day',
           now() - interval '1 day', now() - interval '1 hour'
    FROM sellers s, categories c
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 10000, TRUE, 0 FROM drop_row;

COMMIT;

-- k6 실행 시 -e 옵션으로 넘길 값 확인용.
SELECT d.id AS drop_id, o.id AS option_id, o.total_quantity
FROM drops d JOIN drop_options o ON o.drop_id = d.id
WHERE d.name = 'k6 커넥션풀 상품';
