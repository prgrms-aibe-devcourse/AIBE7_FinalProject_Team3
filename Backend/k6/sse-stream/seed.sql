-- SSE(stock-stream) 동시 연결 부하테스트 전용 GRAB DROP 24개(GR-157).
-- "상품 01"은 인기 상품(상세 구독·주문 집중), 24개 전체는 목록 구독 대상이다(REALTIME_STOCK.md 13.2).
-- 재고는 측정 시간 동안 소진되지 않게 넉넉히 둔다. 재고 고갈이 아니라 변경 알림 전달을 보는 것이 목적이다.
-- compose.loadtest-sse.yaml을 합쳐 띄운 postgres(pg_stat_statements 적재)에서만 동작한다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/sse-stream/seed.sql
BEGIN;

DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name LIKE 'k6 SSE 상품 %'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 SSE 상품 %');
DELETE FROM payments
    WHERE order_id IN (SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6 SSE 상품 %');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 SSE 상품 %');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 SSE 상품 %');
DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 SSE 상품 %');
DELETE FROM drops WHERE name LIKE 'k6 SSE 상품 %';

WITH drop_rows AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 SSE 상품 ' || lpad(n::text, 2, '0'),
           'GR-157 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 hour', now() + interval '1 day',
           now() - interval '1 hour', now() - interval '1 hour'
    FROM generate_series(1, 24) AS n, sellers s, categories c
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 100000, TRUE, 0 FROM drop_rows;

COMMIT;

-- GR-163 비교용 쿼리 통계. 실행 직전에 비워서 이번 회차의 호출 수만 남긴다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT pg_stat_statements_reset();

-- 스크립트들은 keyword로 이 풀을 조회하므로 ID를 넘길 필요는 없다.
SELECT d.id AS drop_id, d.name, o.id AS option_id
FROM drops d JOIN drop_options o ON o.drop_id = d.id
WHERE d.name LIKE 'k6 SSE 상품 %'
ORDER BY d.name;
