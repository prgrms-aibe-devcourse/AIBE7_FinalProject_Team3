-- k6 부하테스트용 더미 데이터. 로컬 Postgres에서 수동 실행한다:
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/seed.sql
-- 재실행 가능하도록 기존 k6 테스트 데이터를 먼저 지운다.
-- 비밀번호(LOCAL 계정 전부 동일): LoadTest1234!
-- 해시는 SecurityConfig.passwordEncoder()와 동일한 파라미터(Argon2id, m=19456, t=2, p=1)로 생성했다.

BEGIN;

-- 재실행 시 이전 시나리오가 만든 주문까지 전부 지워야 drop_options를 다시 만들 수 있다
-- (order_items가 drop_options를 참조하는 FK 때문에 역순으로 지운다).
DELETE FROM stock_reservations
    WHERE order_item_id IN (
        SELECT oi.id FROM order_items oi
        JOIN drops d ON d.id = oi.drop_id
        WHERE d.name = 'k6 부하테스트 상품'
    );
DELETE FROM payment_events
    WHERE payment_id IN (
        SELECT p.id FROM payments p
        JOIN orders o ON o.id = p.order_id
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 부하테스트 상품'
    );
DELETE FROM payment_cancellations
    WHERE payment_id IN (
        SELECT p.id FROM payments p
        JOIN orders o ON o.id = p.order_id
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 부하테스트 상품'
    );
DELETE FROM payments
    WHERE order_id IN (
        SELECT o.id FROM orders o
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 부하테스트 상품'
    );
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 부하테스트 상품');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 부하테스트 상품');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 부하테스트 상품');
DELETE FROM drops WHERE name = 'k6 부하테스트 상품';
DELETE FROM sellers WHERE contact_email = 'k6-seller@loadtest.local';
DELETE FROM users WHERE email IN (
    'k6-admin@loadtest.local', 'k6-seller@loadtest.local', 'k6-buyer@loadtest.local'
);

WITH admin_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-admin@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6admin', 'ADMIN', 'ACTIVE', 'LOCAL'
    )
    RETURNING id
), seller_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6seller', 'USER', 'ACTIVE', 'LOCAL'
    )
    RETURNING id
), buyer_user AS (
    -- k6 로그인에 쓰는 계정. 재고 경쟁 시나리오는 이 계정 하나로 Idempotency-Key만 다르게
    -- 동시 주문을 여러 건 보낸다(orders 테이블은 buyer+idempotency_key만 유니크라 가능).
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-buyer@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6buyer', 'USER', 'ACTIVE', 'LOCAL'
    )
    RETURNING id
), seller_row AS (
    INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
    SELECT seller_user.id, 'k6 부하테스트 브랜드', 'k6-seller@loadtest.local', 'APPROVED',
           admin_user.id, now()
    FROM seller_user, admin_user
    RETURNING id
), drop_row AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at, grab_started_at
    )
    SELECT seller_row.id, categories.id, 'GRAB',
           'k6 부하테스트 상품', 'k6 부하테스트 전용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() - interval '1 day', now() + interval '1 day',
           now() - interval '1 day', now() - interval '1 hour'
    FROM seller_row, categories WHERE categories.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 5, TRUE, 0 FROM drop_row;

COMMIT;

-- k6 실행 시 -e 옵션으로 넘길 값 확인용. 결과의 drop_id/option_id를 복사해 쓴다.
SELECT d.id AS drop_id, o.id AS option_id, o.total_quantity
FROM drops d JOIN drop_options o ON o.drop_id = d.id
WHERE d.name = 'k6 부하테스트 상품';
