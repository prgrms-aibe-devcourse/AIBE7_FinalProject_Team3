-- V35 동일 스키마에서 판매자 주문/대시보드 조회를 함께 재측정하는 전용 fixture.
-- common/accounts-seed.sql을 먼저 실행한 뒤 사용한다.
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM shipments WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM stock_reservations WHERE order_item_id IN (
    SELECT oi.id FROM order_items oi JOIN drops d ON d.id = oi.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payment_events WHERE payment_id IN (
    SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id
    JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payment_cancellations WHERE payment_id IN (
    SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id
    JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payments WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM order_cancellations WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM order_items WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM order_shipping_addresses WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM orders WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM wishes WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drop_embeddings WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drops WHERE name LIKE 'k6-v35-perf-drop-%';

INSERT INTO users (email, password_hash, nickname, role, status, provider)
VALUES ('k6-v35-perf-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6v35seller', 'USER', 'ACTIVE', 'LOCAL')
ON CONFLICT (email) DO NOTHING;

INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
SELECT u.id, 'k6 V35 재측정 브랜드', u.email, 'APPROVED', a.id, now()
FROM users u CROSS JOIN (SELECT id FROM users WHERE email = 'k6-admin@loadtest.local') a
WHERE u.email = 'k6-v35-perf-seller@loadtest.local'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO users (email, password_hash, nickname, role, status, provider)
SELECT 'k6-v35-wisher-' || n || '@loadtest.local',
       '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
       'k6v35wisher' || n, 'USER', 'ACTIVE', 'LOCAL'
FROM generate_series(1, 20) n
ON CONFLICT (email) DO NOTHING;

CREATE TEMP TABLE perf_drops (drop_no INT PRIMARY KEY, id BIGINT, option_id BIGINT) ON COMMIT DROP;
WITH new_drops AS (
    INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                       sale_starts_at, sale_ends_at, published_at, grab_started_at)
    SELECT s.id, c.id, 'GRAB', 'k6-v35-perf-drop-' || lpad(n::text, 4, '0'),
           'V35 performance measurement fixture', 0, '테스트 데이터',
           now() - interval '180 days', now() + interval '180 days', now() - interval '180 days', now() - interval '30 days'
    FROM generate_series(1, 1000) n
    CROSS JOIN sellers s
    CROSS JOIN categories c
    WHERE s.contact_email = 'k6-v35-perf-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id, name
), option_rows AS (
    INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity, sold_quantity,
                              is_active, sort_order)
    SELECT id, 10000, 1000, 1, 19, TRUE, 0 FROM new_drops
    RETURNING id, drop_id
)
INSERT INTO perf_drops
SELECT substring(d.name FROM '[0-9]+$')::INT, d.id, o.id
FROM new_drops d JOIN option_rows o ON o.drop_id = d.id;

WITH new_orders AS (
    INSERT INTO orders (order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash, status,
                        product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                        total_amount, payment_expires_at, created_at)
    SELECT 'GR-V35-PERF-' || n, b.id, d.id, s.id, 'idem-GR-V35-PERF-' || n,
           substr(md5('v35-' || n) || md5('v35b-' || n), 1, 64),
           CASE WHEN n % 20 < 16 THEN 'DELIVERED'
                WHEN n % 20 < 18 THEN 'SHIPPED'
                WHEN n % 20 = 18 THEN 'PAID'
                WHEN n % 20 = 19 THEN 'PAYMENT_PENDING' END,
           'V35 재측정 상품', 'k6 V35 재측정 브랜드', 10000, 0, 10000,
           now() + interval '30 days', now() - (n % 180) * interval '1 day'
    FROM generate_series(1, 20000) n
    CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    CROSS JOIN sellers s
    JOIN perf_drops d ON d.drop_no = 1 + ((n - 1) % 1000)
    WHERE s.contact_email = 'k6-v35-perf-seller@loadtest.local'
    RETURNING id, drop_id, status, total_amount, order_number, created_at
)
INSERT INTO order_shipping_addresses (order_id, recipient_name, recipient_phone, postal_code, address_line1, created_at)
SELECT id, 'k6 V35 수령인', '01099990000', '06236', '서울시 강남구 성능측정로 1', created_at
FROM new_orders;

INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
SELECT o.id, o.drop_id, d.option_id, '기본 옵션', 10000, 1
FROM orders o JOIN perf_drops d ON d.id = o.drop_id
WHERE o.order_number LIKE 'GR-V35-PERF-%';

INSERT INTO payments (order_id, provider, idempotency_key, client_idempotency_key, request_hash,
                      provider_payment_id, amount, status, approved_at)
SELECT o.id, 'MOCK', 'pay-v35-' || o.id, 'pay-client-v35-' || o.id,
       substr(md5('pay-v35-' || o.id) || md5('pay-v35b-' || o.id), 1, 64),
       'mock-v35-' || o.id, o.total_amount,
       CASE WHEN o.status = 'PAYMENT_PENDING' THEN 'PENDING' ELSE 'SUCCEEDED' END,
       CASE WHEN o.status <> 'PAYMENT_PENDING' THEN o.created_at END
FROM orders o WHERE o.order_number LIKE 'GR-V35-PERF-%';

INSERT INTO order_cancellations (order_id, idempotency_key, request_hash, reason, canceled_at)
SELECT id, 'cancel-v35-' || id, repeat('c', 64), '재측정 fixture', created_at
FROM orders WHERE order_number LIKE 'GR-V35-PERF-%' AND status = 'CANCELED';

INSERT INTO shipments (order_id, carrier_code, tracking_number, idempotency_key, request_hash,
                       shipped_at, delivered_at, delivery_stage, stage_updated_at)
SELECT id, 'CJ대한통운', 'TRACK-V35-' || id, 'ship-v35-' || id,
       repeat('s', 64), now() - interval '2 days',
       CASE WHEN status = 'DELIVERED' THEN now() - interval '1 day' END,
       CASE WHEN status = 'DELIVERED' THEN 'OUT_FOR_DELIVERY' ELSE 'COLLECTED' END,
       now() + interval '1 day'
FROM orders WHERE order_number LIKE 'GR-V35-PERF-%' AND status IN ('DELIVERED', 'SHIPPED');

INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT u.id, d.id, now() - interval '1 day'
FROM drops d CROSS JOIN users u
WHERE d.name LIKE 'k6-v35-perf-drop-%' AND u.email LIKE 'k6-v35-wisher-%@loadtest.local';

ANALYZE orders;
ANALYZE payments;
ANALYZE shipments;
ANALYZE drops;
ANALYZE wishes;
COMMIT;

SELECT s.id AS seller_id, count(DISTINCT d.id) AS seeded_drops,
       count(DISTINCT o.id) AS seeded_orders,
       (SELECT count(*) FROM wishes w JOIN drops wd ON wd.id = w.drop_id
        WHERE wd.name LIKE 'k6-v35-perf-drop-%') AS seeded_wishes,
       min(d.id) AS first_drop_id, min(op.id) AS option_id
FROM sellers s JOIN drops d ON d.seller_id = s.id
JOIN orders o ON o.drop_id = d.id JOIN drop_options op ON op.drop_id = d.id
WHERE s.contact_email = 'k6-v35-perf-seller@loadtest.local'
  AND d.name LIKE 'k6-v35-perf-drop-%'
GROUP BY s.id;
