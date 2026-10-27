-- GR-173: 판매자 대시보드 통계 조회 부하테스트용 시드. 전용 판매자 1명(k6-dashboard-seller)에게
-- DROP 200개 + DROP당 결제 완료 주문 5건·위시 20명을 깔아, SellerDashboardService의 집계
-- 쿼리(countOrdersByStatus/countPaymentsByStatus/findDropStats의 LATERAL 조인 2개)가
-- steady-read(GR-153)보다 훨씬 무거운 집계·조인에서도 버티는지 본다.
-- 대시보드 요약·DROP 통계 API는 판매자 전체를 집계하므로(드롭 이름으로 거르지 않음),
-- 공용 k6-seller를 쓰면 다른 soak 시나리오가 남긴 데이터가 섞여 DROP 200개 전제가
-- 깨진다 — 그래서 이 시나리오 전용 판매자 계정을 따로 둔다.
-- 위시는 user_id+drop_id가 유니크라 더미 유저를 20명 따로 둔다.
-- 재실행 가능하도록 기존 데이터부터 지운다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/seller-dashboard/seed.sql
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %');
DELETE FROM payments
    WHERE order_id IN (
        SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name LIKE 'k6 판매자대시보드 상품 %'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %');
DELETE FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %';
DELETE FROM users WHERE email LIKE 'k6-dashboard-wisher-%@loadtest.local';

INSERT INTO users (email, password_hash, nickname, role, status, provider)
SELECT 'k6-dashboard-wisher-' || n || '@loadtest.local',
       '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
       'k6wisher' || n, 'USER', 'ACTIVE', 'LOCAL'
FROM generate_series(1, 20) n;

-- 이 시나리오 전용 판매자. 이미 있으면 건드리지 않는다(재실행 안전).
WITH dashboard_seller_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-dashboard-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6dashboardseller', 'USER', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
)
INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
SELECT dashboard_seller_user.id, 'k6 대시보드 전용 브랜드', 'k6-dashboard-seller@loadtest.local',
       'APPROVED', admin.id, now()
FROM dashboard_seller_user, (SELECT id FROM users WHERE email = 'k6-admin@loadtest.local') admin
ON CONFLICT (user_id) DO NOTHING;

WITH drop_rows AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 판매자대시보드 상품 ' || lpad(n::text, 3, '0'),
           'GR-173 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 day', now() + interval '1 day',
           now() - interval '1 day', now() - interval '1 hour'
    FROM generate_series(1, 200) AS n, sellers s, categories c
    WHERE s.contact_email = 'k6-dashboard-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, sold_quantity, is_active, sort_order)
SELECT id, 10000, 1000, 50, TRUE, 0 FROM drop_rows;

INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT u.id, d.id, now() - interval '1 hour'
FROM drops d
JOIN users u ON u.email LIKE 'k6-dashboard-wisher-%@loadtest.local'
WHERE d.name LIKE 'k6 판매자대시보드 상품 %';

WITH new_orders AS (
    INSERT INTO orders (
        order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
        product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
        total_amount, recipient_name, recipient_phone, postal_code, address_line1,
        payment_expires_at, paid_at
    )
    SELECT 'GR173-' || d.id || '-' || gs, b.id, d.id,
           'idem-GR173-' || d.id || '-' || gs,
           substr(md5('GR173-' || d.id || '-' || gs) || md5('GR173b-' || d.id || '-' || gs), 1, 64),
           'PAID', 'k6 판매자대시보드 상품', 'k6 대시보드 전용 브랜드', 10000, 0, 10000,
           'k6 테스터', '01099990000', '06236', '서울시 강남구 테스트로 1',
           now() + interval '15 minutes', now() - interval '1 hour'
    FROM drops d, generate_series(1, 5) gs,
         (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    WHERE d.name LIKE 'k6 판매자대시보드 상품 %'
    RETURNING id, drop_id
), order_option AS (
    SELECT o.id AS order_id, o.drop_id, op.id AS option_id
    FROM new_orders o
    JOIN drop_options op ON op.drop_id = o.drop_id
)
INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
SELECT order_id, drop_id, option_id, '기본 옵션', 10000, 1 FROM order_option;

INSERT INTO payments (
    order_id, provider, idempotency_key, client_idempotency_key, request_hash,
    provider_payment_id, amount, status, approved_at
)
SELECT o.id, 'MOCK', 'pay-idem-GR173-' || o.id, 'pay-client-idem-GR173-' || o.id,
       substr(md5('GR173-pay-' || o.id) || md5('GR173-pay2-' || o.id), 1, 64),
       'mock_GR173_' || o.id, o.total_amount, 'SUCCEEDED', o.paid_at
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 판매자대시보드 상품 %';

COMMIT;

SELECT count(*) AS seeded_drops FROM drops WHERE name LIKE 'k6 판매자대시보드 상품 %';
