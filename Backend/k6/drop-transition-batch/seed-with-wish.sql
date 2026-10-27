-- GR-171 비교군: seed.sql과 같은 DROP 600개지만 각각 WISH 50명을 둬서, 전이 시
-- 발행되는 DropGrabStartedEvent -> WishSaleNoticeMailListener(AFTER_COMMIT)의
-- 수신자 조회(findSaleStartMailRecipients)가 같은 스케줄러 스레드에서 동기 실행되는
-- 경로를 실제로 태운다. 메일 발송 자체의 부하(수만 명 규모)는 GR-170 범위이므로,
-- 여기서는 "이벤트 발행이 배치 루프를 지연시키는지"만 보기 위한 적당한 수신자 수(50)만 둔다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/drop-transition-batch/seed-with-wish.sql
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배치전이 WISH포함');
DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배치전이 WISH포함');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배치전이 WISH포함');
DELETE FROM drops WHERE name = 'k6 배치전이 WISH포함';
DELETE FROM users WHERE email LIKE 'k6-batchtransition-%@loadtest.local';

WITH drop_rows AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at
    )
    SELECT s.id, c.id, 'WISH', 'k6 배치전이 WISH포함',
           'GR-171 대량 동시 전이(메일 트리거 포함) 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() + interval '1 hour', now() + interval '1 day', now() - interval '1 hour'
    FROM sellers s, categories c, generate_series(1, 600) AS gs
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
),
options_ins AS (
    INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
    SELECT id, 10000, 1, TRUE, 0 FROM drop_rows
    RETURNING 1
),
-- DROP당 WISH 50명(=BCC 1통). 같은 더미 사용자 50명이 600개 DROP 전부를 WISH한다.
wish_users AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    SELECT 'k6-batchtransition-' || gs || '@loadtest.local',
           '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
           'k6batchtx' || gs,
           'USER', 'ACTIVE', 'LOCAL'
    FROM generate_series(1, 50) AS gs
    RETURNING id
)
INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT wu.id, dr.id, now()
FROM wish_users wu CROSS JOIN drop_rows dr;

COMMIT;

BEGIN;
UPDATE drops SET sale_starts_at = now() + interval '20 seconds' WHERE name = 'k6 배치전이 WISH포함';
COMMIT;

SELECT count(*) AS drop_count, min(sale_starts_at), max(sale_starts_at)
FROM drops WHERE name = 'k6 배치전이 WISH포함';
