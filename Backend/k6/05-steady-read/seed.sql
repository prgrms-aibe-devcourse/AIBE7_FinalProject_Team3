-- 평상시 조회(목록/상세) 부하테스트 전용 더미 DROP 15개(GR-153).
-- 하나는 "인기 드롭"(반복 조회용)으로 쓰고, 나머지 14개는 랜덤 조회 풀로 쓴다.
-- 재실행 가능하도록 이 시나리오가 만든 DROP부터 먼저 지운다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/05-steady-read/seed.sql
BEGIN;

DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 평상시조회 상품 %');
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 평상시조회 상품 %');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 평상시조회 상품 %');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 평상시조회 상품 %');
DELETE FROM drops WHERE name LIKE 'k6 평상시조회 상품 %';

WITH drop_rows AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 평상시조회 상품 ' || lpad(n::text, 2, '0'),
           'PERF-003 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 day', now() + interval '1 day',
           now() - interval '1 day', now() - interval '1 hour'
    FROM generate_series(1, 15) AS n, sellers s, categories c
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id, name
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 1000, TRUE, 0 FROM drop_rows;

COMMIT;

-- k6 실행 시 keyword로 이 풀을 조회하므로 ID를 넘길 필요는 없다.
-- "상품 01"이 인기 드롭(HOT_KEYWORD), 전체가 랜덤 풀(POOL_KEYWORD)이다.
SELECT d.id AS drop_id, d.name
FROM drops d
WHERE d.name LIKE 'k6 평상시조회 상품 %'
ORDER BY d.name;
