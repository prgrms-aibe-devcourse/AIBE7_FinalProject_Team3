-- GR-174: 판매자 주문 목록/상세 조회 부하테스트용 시드. 전용 판매자 1명(k6-order-seller)에게
-- DROP 1개(GRAB, 재고 넉넉히) + 결제 완료 주문 1000건을 깔아, SellerOrderController의
-- 목록(findSellerOrders, LATERAL로 최근 결제상태 조인)·상세(OrderDetailReader, 품목·결제·
-- 배송 3개 쿼리) 조회가 평상시 수준 동시 조회를 버티는지 본다.
-- 대시보드 API(GR-173)와 같은 이유로 판매자 전체를 집계·조회하는 API라, 다른 soak
-- 시나리오와 섞이지 않게 이 시나리오 전용 판매자 계정을 쓴다.
-- 재실행 가능하도록 기존 데이터부터 지운다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/seller-order-read/seed.sql
SET client_encoding = 'UTF8';
BEGIN;

-- order_burst(k6)가 실제 /api/v1/orders API로 주문을 만들면 체크아웃 플로우를 타서
-- shipments·stock_reservations에도 행이 생긴다(seed.sql이 SQL로 직접 꽂는 1000건과
-- 달리 FK가 RESTRICT라 먼저 지워야 order_items/orders 삭제가 막히지 않는다).
DELETE FROM shipments
    WHERE order_id IN (
        SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 판매자주문조회 상품'
    );
DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name = 'k6 판매자주문조회 상품'
    );
DELETE FROM payments
    WHERE order_id IN (
        SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 판매자주문조회 상품'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 판매자주문조회 상품');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 판매자주문조회 상품');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 판매자주문조회 상품');
DELETE FROM drops WHERE name = 'k6 판매자주문조회 상품';

-- 이 시나리오 전용 판매자. 이미 있으면 건드리지 않는다(재실행 안전).
WITH order_seller_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-order-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6orderseller', 'USER', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
)
INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
SELECT order_seller_user.id, 'k6 주문조회 전용 브랜드', 'k6-order-seller@loadtest.local',
       'APPROVED', admin.id, now()
FROM order_seller_user, (SELECT id FROM users WHERE email = 'k6-admin@loadtest.local') admin
ON CONFLICT (user_id) DO NOTHING;

-- 테스트 중 k6의 order_burst 시나리오가 실제 주문 API로 계속 주문을 추가하므로
-- (목록/상세 조회가 "오픈 직후 주문 폭주" 시점과 겹칠 때도 버티는지 보려고, 작업 내용 3)
-- 재고를 넉넉히 둬 재고 고갈로 인한 409는 이 시나리오의 관심사가 아니게 한다.
WITH drop_row AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 판매자주문조회 상품', 'GR-174 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 day', now() + interval '1 day',
           now() - interval '1 day', now() - interval '1 hour'
    FROM sellers s, categories c
    WHERE s.contact_email = 'k6-order-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 500000, TRUE, 0 FROM drop_row;

WITH new_orders AS (
    INSERT INTO orders (
        order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
        product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
        total_amount, recipient_name, recipient_phone, postal_code, address_line1,
        payment_expires_at, paid_at
    )
    SELECT 'GR174-' || d.id || '-' || gs, b.id, d.id,
           'idem-GR174-' || d.id || '-' || gs,
           substr(md5('GR174-' || d.id || '-' || gs) || md5('GR174b-' || d.id || '-' || gs), 1, 64),
           'PAID', 'k6 판매자주문조회 상품', 'k6 주문조회 전용 브랜드', 10000, 0, 10000,
           'k6 테스터', '01099990000', '06236', '서울시 강남구 테스트로 1',
           now() + interval '15 minutes', now() - interval '1 hour'
    FROM drops d, generate_series(1, 1000) gs,
         (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    WHERE d.name = 'k6 판매자주문조회 상품'
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
SELECT o.id, 'MOCK', 'pay-idem-GR174-' || o.id, 'pay-client-idem-GR174-' || o.id,
       substr(md5('GR174-pay-' || o.id) || md5('GR174-pay2-' || o.id), 1, 64),
       'mock_GR174_' || o.id, o.total_amount, 'SUCCEEDED', o.paid_at
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 판매자주문조회 상품';

COMMIT;

-- k6 실행 시 -e 옵션으로 넘길 값 확인용.
SELECT d.id AS drop_id, o.id AS option_id,
       (SELECT count(*) FROM orders WHERE drop_id = d.id) AS seeded_orders
FROM drops d JOIN drop_options o ON o.drop_id = d.id
WHERE d.name = 'k6 판매자주문조회 상품';
