-- 공개 DROP 목록 쿼리 대용량 측정용 seed(GR-164).
-- 기본 규모: DROP 5,000건(옵션 1~4개·이미지), 회원 500명, WISH 약 7만 건, 주문 약 3만 건.
-- common/accounts-seed.sql을 먼저 실행해야 한다(k6 판매자 계정을 쓴다).
--   docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -f - < k6/drop-list-scale/seed.sql
-- 규모를 바꿀 때는 -v drops=10000 -v max_wishes=80 -v max_orders=40 처럼 넘긴다.
--
-- 재실행 가능하도록 이 시나리오가 만든 DROP('k6 대용량 %')과 딸린 행부터 지운다. 회원은 지우지 않고 재사용한다.
-- setseed로 난수를 고정해 같은 파라미터면 같은 분포가 나온다(행 ID와 now() 기준 시각은 실행마다 다르다).
-- 개선 전후 비교는 매번 이 파일로 다시 만든 직후 같은 순서로 측정한다.
--
-- 앱 스케줄러가 측정 중에 데이터를 바꾸지 않도록 시각과 상태를 고른다.
--   - WISH는 판매 시작을 3일 이후로 둬 판매 시작 전환·오픈 임박 알림 대상이 되지 않게 한다.
--   - GRAB은 판매 종료를 3일 이후로 둬 종료 배치 대상이 되지 않게 한다.
--   - 미결제 주문은 PAYMENT_PENDING 대신 EXPIRED로 넣어 결제 대기 만료 배치가 재고를 되돌리지 않게 한다.
--   - payments·shipments 행은 만들지 않는다(목록 쿼리는 orders·order_items만 본다).

\if :{?drops}
\else
\set drops 5000
\endif
\if :{?users}
\else
\set users 500
\endif
\if :{?max_wishes}
\else
\set max_wishes 60
\endif
\if :{?max_orders}
\else
\set max_orders 30
\endif

BEGIN;

SELECT setseed(0.164);

DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM demand_analysis_snapshots
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM demand_explanations
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drop_stock_recommendations
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM order_items
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drop_images
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drop_option_value_maps
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drop_option_values
    WHERE group_id IN (SELECT g.id FROM drop_option_groups g JOIN drops d ON d.id = g.drop_id
                       WHERE d.name LIKE 'k6 대용량 %');
DELETE FROM drop_option_groups
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6 대용량 %');
DELETE FROM drops WHERE name LIKE 'k6 대용량 %';

-- WISH·주문 주체. uq_wishes_user_drop 때문에 DROP당 WISH 수 상한(max_wishes)보다 많아야 한다.
-- 비밀번호 해시는 accounts-seed.sql과 같다(LoadTest1234!). 로그인에는 쓰지 않는다.
INSERT INTO users (email, password_hash, nickname, role, status, provider)
SELECT 'k6-scale-' || lpad(u::text, 4, '0') || '@loadtest.local',
       '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
       'k6scale' || lpad(u::text, 4, '0'), 'USER', 'ACTIVE', 'LOCAL'
FROM generate_series(1, :users) AS u
ON CONFLICT (email) DO NOTHING;

CREATE TEMP TABLE k6s_users ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY email) - 1 AS idx, id
FROM users
WHERE email LIKE 'k6-scale-%@loadtest.local';

-- DROP 계획. n으로 상태·카테고리·키워드·품절을 결정한다.
--   상태: DRAFT 5%, WISH 35%, GRAB 35%, ENDED 20%, CANCELED 5%
--   키워드: 이름 끝에 단어 19종 중 하나(각 약 5%). 'k6 대용량'은 전체가 걸리는 최악 조건이다.
--   단어(n % 19)·카테고리((n / 20) % 8)는 상태(n % 20)와 겹치지 않는 주기로 골라 특정 상태에 쏠리지 않게 한다.
--   품절: GRAB의 약 15%, ENDED의 약 40%는 활성 SKU 가용 재고 0.
CREATE TEMP TABLE k6s_plan ON COMMIT DROP AS
SELECT n,
       CASE
           WHEN n % 20 = 0 THEN 'DRAFT'
           WHEN n % 20 BETWEEN 1 AND 7 THEN 'WISH'
           WHEN n % 20 BETWEEN 8 AND 14 THEN 'GRAB'
           WHEN n % 20 BETWEEN 15 AND 18 THEN 'ENDED'
           ELSE 'CANCELED'
       END AS status,
       'k6 대용량 상품 ' || lpad(n::text, 5, '0') || ' '
           || (ARRAY['후드', '스니커즈', '토트백', '립밤', '이어폰', '머그컵', '원두', '피규어',
                     '셔츠', '부츠', '지갑', '세럼', '키보드', '쿠션', '그래놀라', '포스터',
                     '자켓', '샌들', '모자'])[n % 19 + 1] AS name,
       (ARRAY['FASHION', 'SHOES', 'BAG_ACC', 'BEAUTY', 'DIGITAL', 'LIVING', 'FOOD', 'HOBBY'])[(n / 20) % 8 + 1] AS category_code,
       1 + n % 4 AS option_count,
       random() < CASE WHEN n % 20 BETWEEN 8 AND 14 THEN 0.15
                       WHEN n % 20 BETWEEN 15 AND 18 THEN 0.40
                       ELSE 0 END AS sold_out,
       now() - (n % 60) * interval '1 day' - (n % 1440) * interval '1 minute' AS published_at,
       -- 행마다 난수를 뽑아 두어야 LATERAL generate_series가 DROP마다 다른 개수를 만든다.
       CASE WHEN n % 20 BETWEEN 1 AND 18 THEN floor(:max_wishes * random() ^ 3)::int ELSE 0 END AS wish_count,
       CASE WHEN n % 20 BETWEEN 8 AND 18 THEN floor(:max_orders * random() ^ 2)::int ELSE 0 END AS order_count
FROM generate_series(1, :drops) AS n;

INSERT INTO drops (
    seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
    sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason, cancel_reason
)
SELECT s.id, c.id, p.status, p.name,
       'GR-164 대용량 측정용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
       3000, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
       t.starts_at, t.starts_at + t.length,
       CASE WHEN p.status = 'DRAFT' THEN NULL ELSE p.published_at END,
       CASE WHEN p.status IN ('GRAB', 'ENDED') THEN t.starts_at END,
       CASE WHEN p.status = 'ENDED' THEN t.starts_at + t.length
            WHEN p.status = 'CANCELED' THEN now() - interval '1 day' END,
       CASE WHEN p.status = 'ENDED' THEN 'TIME_EXPIRED'
            WHEN p.status = 'CANCELED' THEN 'SELLER_CANCELED' END,
       CASE WHEN p.status = 'CANCELED' THEN '부하테스트 더미 취소' END
FROM k6s_plan p
CROSS JOIN LATERAL (
    SELECT CASE p.status
               WHEN 'WISH' THEN now() + interval '3 days' + (p.n % 27) * interval '1 day'
               WHEN 'GRAB' THEN now() - interval '1 day' - (p.n % 10) * interval '1 day'
               WHEN 'ENDED' THEN now() - interval '30 days' - (p.n % 30) * interval '1 day'
               ELSE now() + interval '5 days'
           END AS starts_at,
           CASE p.status
               WHEN 'GRAB' THEN interval '14 days' + (p.n % 10) * interval '1 day'
               ELSE interval '7 days'
           END AS length
) t
JOIN categories c ON c.code = p.category_code
CROSS JOIN sellers s
WHERE s.contact_email = 'k6-seller@loadtest.local'
ORDER BY p.n;

CREATE TEMP TABLE k6s_drops ON COMMIT DROP AS
SELECT p.*, d.id AS drop_id
FROM k6s_plan p
JOIN drops d ON d.name = p.name;

-- 옵션. 수량은 주문을 넣은 뒤 판매량에 맞춰 채운다. 4개짜리 DROP의 1/3은 마지막 SKU를 비활성으로 둔다(상태와 겹치지 않게 n % 3으로 고른다).
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT k.drop_id, 10000 + (k.n % 50) * 1000 + o * 500, 0,
       NOT (k.option_count = 4 AND o = 3 AND k.n % 3 = 0), o
FROM k6s_drops k
CROSS JOIN LATERAL generate_series(0, k.option_count - 1) AS o;

-- 옵션 그룹(GR-181). 상세 조회는 그룹·값·SKU 매핑까지 읽으므로 실제 상품처럼 구성한다(목록 쿼리는 읽지 않는다).
--   SKU 1개: 그룹 없음 / 2~3개: 색상 1그룹(값 = SKU 수) / 4개: 색상 2값 × 사이즈 2값
INSERT INTO drop_option_groups (drop_id, name, sort_order)
SELECT k.drop_id, g.name, g.sort_order
FROM k6s_drops k
CROSS JOIN LATERAL (VALUES ('색상', 0), ('사이즈', 1)) AS g(name, sort_order)
WHERE k.option_count >= 2 AND (g.name = '색상' OR k.option_count = 4);

INSERT INTO drop_option_values (group_id, value, sort_order)
SELECT g.id, (CASE g.name WHEN '색상' THEN ARRAY['블랙', '화이트', '네이비'] ELSE ARRAY['M', 'L'] END)[v + 1], v
FROM k6s_drops k
JOIN drop_option_groups g ON g.drop_id = k.drop_id
CROSS JOIN LATERAL generate_series(0, CASE WHEN g.name = '색상' AND k.option_count <> 4 THEN k.option_count - 1 ELSE 1 END) AS v;

INSERT INTO drop_option_value_maps (option_id, group_id, value_id, drop_id)
SELECT o.id, g.id, val.id, k.drop_id
FROM k6s_drops k
JOIN drop_options o ON o.drop_id = k.drop_id
JOIN drop_option_groups g ON g.drop_id = k.drop_id
JOIN drop_option_values val ON val.group_id = g.id
 AND val.sort_order = CASE
        WHEN k.option_count <> 4 THEN o.sort_order
        WHEN g.name = '색상' THEN o.sort_order / 2
        ELSE o.sort_order % 2
     END;

-- 썸네일 후보 GALLERY 1~3장, DETAIL 1장(목록은 DETAIL을 쓰지 않는다).
INSERT INTO drop_images (drop_id, image_url, sort_order, alt_text, image_type)
SELECT k.drop_id, 'https://example.invalid/k6/scale/' || k.n || '/g' || i || '.jpg', i,
       k.name || ' 이미지 ' || i, 'GALLERY'
FROM k6s_drops k
CROSS JOIN LATERAL generate_series(0, k.n % 3) AS i;

INSERT INTO drop_images (drop_id, image_url, sort_order, alt_text, image_type)
SELECT k.drop_id, 'https://example.invalid/k6/scale/' || k.n || '/d0.jpg', 0,
       k.name || ' 상세', 'DETAIL'
FROM k6s_drops k;

-- WISH. DROP당 개수는 max_wishes * random()^3(소수 상품에 쏠림), 약 20%는 취소 상태다.
-- 회원 선택이 DROP 안에서 겹치지 않도록 연속 구간을 쓴다(max_wishes <= users여야 한다).
INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
SELECT u.id, w.drop_id, w.activated_at,
       CASE WHEN random() < 0.2 THEN w.activated_at + interval '1 hour' END
FROM (
    SELECT k.drop_id, k.n, j,
           k.published_at + (j % 48) * interval '1 hour' AS activated_at
    FROM k6s_drops k
    CROSS JOIN LATERAL generate_series(1, k.wish_count) AS j
) w
JOIN k6s_users u ON u.idx = (w.n * 7 + w.j) % :users;

-- 주문. GRAB·ENDED DROP에만 넣고, DROP당 개수는 max_orders * random()^2.
-- 상태: 결제 확정(PAID·DELIVERED) 70%, 결제 후 취소 10%, 미결제 만료 20%.
-- 인기 GRAB 집계는 paid_at IS NOT NULL AND canceled_at IS NULL만 센다.
CREATE TEMP TABLE k6s_orders ON COMMIT DROP AS
SELECT k.drop_id, k.n, j, k.name,
       floor(random() * k.option_count)::int AS option_sort,
       1 + floor(random() * 3)::int AS quantity,
       random() AS r,
       now() - interval '12 hours' - (j % 72) * interval '1 hour' AS created_at
FROM k6s_drops k
CROSS JOIN LATERAL generate_series(1, k.order_count) AS j;

-- 비활성 SKU로는 주문하지 않는다.
UPDATE k6s_orders ko
SET option_sort = 0
FROM drop_options o
WHERE o.drop_id = ko.drop_id AND o.sort_order = ko.option_sort AND NOT o.is_active;

INSERT INTO orders (
    order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount, total_amount,
    recipient_name, recipient_phone, postal_code, address_line1,
    payment_expires_at, paid_at, canceled_at, expired_at, cancel_reason, created_at, updated_at
)
SELECT 'K6S-' || ko.n || '-' || ko.j, u.id, ko.drop_id,
       'k6-scale-' || ko.n || '-' || ko.j, md5(ko.n || '-' || ko.j),
       CASE WHEN ko.r < 0.5 THEN 'PAID' WHEN ko.r < 0.7 THEN 'DELIVERED'
            WHEN ko.r < 0.8 THEN 'CANCELED' ELSE 'EXPIRED' END,
       ko.name, 'k6 부하테스트 브랜드',
       o.unit_price * ko.quantity, 3000, o.unit_price * ko.quantity + 3000,
       'k6 수령인', '010-0000-0000', '00000', '부하테스트 더미 주소',
       ko.created_at + interval '10 minutes',
       CASE WHEN ko.r < 0.8 THEN ko.created_at + interval '1 minute' END,
       CASE WHEN ko.r >= 0.7 AND ko.r < 0.8 THEN ko.created_at + interval '2 hours' END,
       CASE WHEN ko.r >= 0.8 THEN ko.created_at + interval '10 minutes' END,
       CASE WHEN ko.r >= 0.7 AND ko.r < 0.8 THEN '부하테스트 더미 취소' END,
       ko.created_at, ko.created_at
FROM k6s_orders ko
JOIN drop_options o ON o.drop_id = ko.drop_id AND o.sort_order = ko.option_sort
JOIN k6s_users u ON u.idx = (ko.n * 13 + ko.j) % :users;

INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
SELECT od.id, ko.drop_id, o.id, '옵션 ' || ko.option_sort, o.unit_price, ko.quantity
FROM k6s_orders ko
JOIN orders od ON od.order_number = 'K6S-' || ko.n || '-' || ko.j
JOIN drop_options o ON o.drop_id = ko.drop_id AND o.sort_order = ko.option_sort;

-- SKU 수량을 주문과 맞춘다. 판매량은 결제 확정·미취소 주문 수량 합이고, 가용 재고는 품절이면 0이다.
UPDATE drop_options o
SET sold_quantity = s.sold,
    total_quantity = s.sold + CASE
        WHEN k.sold_out OR NOT o.is_active THEN 0
        WHEN k.status = 'WISH' THEN 50 + (k.n % 50)
        ELSE 1 + (k.n + o.sort_order) % 40
    END
FROM k6s_drops k
JOIN drop_options ko ON ko.drop_id = k.drop_id
LEFT JOIN (
    SELECT oi.option_id, SUM(oi.quantity) AS sold
    FROM order_items oi
    JOIN orders od ON od.id = oi.order_id
    WHERE od.paid_at IS NOT NULL AND od.canceled_at IS NULL
    GROUP BY oi.option_id
) paid ON paid.option_id = ko.id
CROSS JOIN LATERAL (SELECT COALESCE(paid.sold, 0)::int AS sold) s
WHERE o.id = ko.id;

COMMIT;

-- ANALYZE만 하면 visibility map이 비어 있어 autovacuum이 언제 도느냐에 따라 Index Only Scan 여부가 바뀐다
-- (측정마다 계획이 달라짐). VACUUM까지 해 seed 직후 상태를 항상 같게 만든다. 트랜잭션 밖에서 실행해야 한다.
VACUUM (ANALYZE) drops, drop_options, drop_option_groups, drop_option_values, drop_option_value_maps,
    drop_images, wishes, orders, order_items;

-- 이 seed 밖의 공개 DROP(다른 시나리오 seed 등)도 공개 목록 쿼리 대상이라 측정에 섞인다.
-- 있으면 경고만 하고 계속한다. 건수가 의미 있게 크면 DB를 초기화하고 다시 넣거나 결과 해석에 감안한다.
DO $$
DECLARE
    others BIGINT;
BEGIN
    SELECT COUNT(*) INTO others
    FROM drops
    WHERE status NOT IN ('DRAFT') AND (name IS NULL OR name NOT LIKE 'k6 대용량 %');
    IF others > 0 THEN
        RAISE WARNING '이 seed 밖의 공개 DROP %건이 측정 대상에 섞인다', others;
    END IF;
END $$;

-- 확인용 분포.
SELECT d.status, COUNT(*) AS drops,
       COUNT(*) FILTER (WHERE NOT EXISTS (
           SELECT 1 FROM drop_options o
           WHERE o.drop_id = d.id AND o.is_active
             AND o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity > 0
       )) AS sold_out
FROM drops d
WHERE d.name LIKE 'k6 대용량 %'
GROUP BY d.status
ORDER BY d.status;

SELECT
    (SELECT COUNT(*) FROM drop_options o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6 대용량 %') AS options,
    (SELECT COUNT(*) FROM wishes w JOIN drops d ON d.id = w.drop_id WHERE d.name LIKE 'k6 대용량 %') AS wishes,
    (SELECT COUNT(*) FROM wishes w JOIN drops d ON d.id = w.drop_id
      WHERE d.name LIKE 'k6 대용량 %' AND w.canceled_at IS NULL) AS active_wishes,
    (SELECT COUNT(*) FROM orders od JOIN drops d ON d.id = od.drop_id WHERE d.name LIKE 'k6 대용량 %') AS orders,
    (SELECT COUNT(*) FROM orders od JOIN drops d ON d.id = od.drop_id
      WHERE d.name LIKE 'k6 대용량 %' AND od.paid_at IS NOT NULL AND od.canceled_at IS NULL) AS paid_orders;
