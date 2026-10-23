-- 드롭 오픈 스파이크 시나리오 전용 DROP/옵션(GR-152). WISH 상태로 만들고
-- sale_starts_at을 가까운 미래로 둬서, DropTransitionScheduler(10초 주기)가
-- k6 실행 도중 WISH -> GRAB으로 자동 전환하게 한다.
-- spike.js의 OPEN_DELAY_SECONDS(기본 30)와 아래 간격을 맞춰야 한다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/04-drop-open-spike/seed.sql
BEGIN;

DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name = 'k6 오픈스파이크 상품'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 오픈스파이크 상품');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 오픈스파이크 상품');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 오픈스파이크 상품');
DELETE FROM drops WHERE name = 'k6 오픈스파이크 상품';

WITH drop_row AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at
    )
    SELECT s.id, c.id, 'WISH',
           'k6 오픈스파이크 상품', 'PERF-002 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() + interval '30 seconds', now() + interval '1 day',
           now() - interval '1 hour'
    FROM sellers s, categories c
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
-- 재고 경쟁(01)과 달리 재고 고갈이 아니라 조회+주문 유입량 자체를 보는 것이 목적이라 넉넉히 둔다.
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 200, TRUE, 0 FROM drop_row;

COMMIT;

-- k6 실행 시 -e 옵션으로 넘길 값 확인용. sale_starts_at도 같이 보여줘 타이밍을 맞춘다.
SELECT d.id AS drop_id, o.id AS option_id, o.total_quantity, d.sale_starts_at
FROM drops d JOIN drop_options o ON o.drop_id = d.id
WHERE d.name = 'k6 오픈스파이크 상품';
