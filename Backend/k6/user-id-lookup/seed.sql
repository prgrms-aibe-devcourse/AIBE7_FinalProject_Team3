-- GR-201: 인증 요청의 회원 ID 변환 기준선 측정용 시드. 공통 계정 k6-buyer에게 일반 사용자 수준의
-- 결제 완료 주문 10건을 깔아, 내 주문 목록(GET /api/v1/orders)이 빈 목록이 아니라 실제 행을 읽게 한다.
-- 목록 API는 orders 테이블만 읽지만, 데이터가 실제와 어긋나지 않도록 주문 항목·결제도 함께 넣는다.
--
-- 10건은 목록 기본 페이지 크기(20)보다 작다. Spring Data는 첫 페이지 결과가 페이지 크기보다 적으면
-- 전체 개수 쿼리를 생략하므로, 주문이 몇 건 안 되는 일반 사용자와 같은 쿼리 구성이 된다.
--
-- 다른 시나리오와 섞이지 않게 전용 판매자(k6-idlookup-seller)와 전용 DROP을 쓴다(seller-order-read와 같은 방식).
-- 재실행 가능하도록 이 시나리오의 데이터부터 지운다. 다른 시나리오가 k6-buyer에게 넣은 주문은 지우지 않으므로,
-- 마지막 조회의 buyer_total_orders가 seeded_orders(10)와 다르면 측정 전에 그 사실을 기록한다.
-- 공통 계정 시드(k6/common/accounts-seed.sql)를 먼저 실행한다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/user-id-lookup/seed.sql
SET client_encoding = 'UTF8';
BEGIN;

-- 체크아웃 플로우로 생긴 행이 있으면 FK(RESTRICT) 때문에 먼저 지운다(seller-order-read와 같음).
DELETE FROM shipments
    WHERE order_id IN (
        SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 ID변환측정 상품'
    );
DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name = 'k6 ID변환측정 상품'
    );
-- 시드 주문을 API로 취소하거나 결제 이벤트가 쌓였으면 결제 하위 기록이 payments를 참조하므로 먼저 지운다.
DELETE FROM payment_events
    WHERE payment_id IN (
        SELECT p.id FROM payments p
        JOIN orders o ON o.id = p.order_id
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 ID변환측정 상품'
    );
DELETE FROM payment_cancellations
    WHERE payment_id IN (
        SELECT p.id FROM payments p
        JOIN orders o ON o.id = p.order_id
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 ID변환측정 상품'
    );
DELETE FROM payments
    WHERE order_id IN (
        SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 ID변환측정 상품'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
-- DROP 상태 전환 알림·임베딩·수요 분석 배치·찜이 이 DROP을 참조할 수 있어 DROP보다 먼저 지운다(drop-list-scale/seed.sql과 같은 대상).
DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM drop_embeddings
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM demand_analysis_snapshots
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM demand_explanations
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM drop_stock_recommendations
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 ID변환측정 상품');
DELETE FROM drops WHERE name = 'k6 ID변환측정 상품';

-- 이 시나리오 전용 판매자. 이미 있으면 건드리지 않는다(재실행 안전). 비밀번호는 공통 계정과 같다.
WITH idlookup_seller_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-idlookup-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6idlookupseller', 'USER', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
)
INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
SELECT idlookup_seller_user.id, 'k6 ID변환측정 전용 브랜드', 'k6-idlookup-seller@loadtest.local',
       'APPROVED', admin.id, now()
FROM idlookup_seller_user, (SELECT id FROM users WHERE email = 'k6-admin@loadtest.local') admin
ON CONFLICT (user_id) DO NOTHING;

-- 주문만 필요하고 이 DROP으로 새 주문을 받지 않는다. 주문의 FK 대상으로만 둔다.
WITH drop_row AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT s.id, c.id, 'GRAB',
           'k6 ID변환측정 상품', 'GR-201 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '11 days', now() + interval '7 days',
           now() - interval '11 days', now() - interval '11 days'
    FROM sellers s, categories c
    WHERE s.contact_email = 'k6-idlookup-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
-- 아래 결제 완료 주문 10건과 맞도록 판매 수량을 10으로 둔다.
INSERT INTO drop_options (drop_id, unit_price, total_quantity, sold_quantity, is_active, sort_order)
SELECT id, 10000, 1000, 10, TRUE, 0 FROM drop_row;

-- 주문 시각을 하루씩 벌려 목록 정렬(created_at DESC, id DESC)이 실제처럼 시각으로 정해지게 한다.
-- payment_expires_at은 created_at보다 뒤여야 한다(ck_orders_payment_expiry).
WITH new_orders AS (
    INSERT INTO orders (
        order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
        product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
        total_amount, recipient_name, recipient_phone, postal_code, address_line1,
        payment_expires_at, paid_at, created_at, updated_at
    )
    SELECT 'GR201-' || d.id || '-' || gs, b.id, d.id,
           'idem-GR201-' || d.id || '-' || gs,
           substr(md5('GR201-' || d.id || '-' || gs) || md5('GR201b-' || d.id || '-' || gs), 1, 64),
           'PAID', 'k6 ID변환측정 상품', 'k6 ID변환측정 전용 브랜드', 10000, 0, 10000,
           'k6 테스터', '01099990000', '06236', '서울시 강남구 테스트로 1',
           now() - make_interval(days => gs) + interval '15 minutes',
           now() - make_interval(days => gs) + interval '1 minute',
           now() - make_interval(days => gs),
           now() - make_interval(days => gs) + interval '1 minute'
    FROM drops d, generate_series(1, 10) gs,
         (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    WHERE d.name = 'k6 ID변환측정 상품'
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
SELECT o.id, 'MOCK', 'pay-idem-GR201-' || o.id, 'pay-client-idem-GR201-' || o.id,
       substr(md5('GR201-pay-' || o.id) || md5('GR201-pay2-' || o.id), 1, 64),
       'mock_GR201_' || o.id, o.total_amount, 'SUCCEEDED', o.paid_at
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 ID변환측정 상품';

COMMIT;

-- 측정 전 기록용. buyer_total_orders가 10이 아니면 다른 시나리오의 주문이 남아 있는 것이다.
SELECT (SELECT count(*) FROM orders o JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 ID변환측정 상품') AS seeded_orders,
       (SELECT count(*) FROM orders o JOIN users u ON u.id = o.buyer_id
        WHERE u.email = 'k6-buyer@loadtest.local') AS buyer_total_orders;
