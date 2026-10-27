-- GR-170 완료조건 3 전용: 서로 다른 스케줄러 tick에서 전이되는 DROP 두 개를 만들어,
-- 먼저 전이된 대량 DROP의 발송(noticeMailTaskExecutor, pool=1)이 뒤이어 전이되는
-- 소량 DROP의 알림 발송을 지연시키는지 본다. wish-mail-storm/seed.sql(동시 전이, 완료조건 1/2)과는
-- 별개 시나리오라 DROP 이름을 다르게 둔다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/wish-mail-storm/seed-stagger.sql
-- 클라이언트 인코딩이 UTF8이 아니면 DROP 이름의 한글 리터럴이 깨져 DELETE/WHERE가
-- 기존 행과 매치되지 않는다(재실행 시 FK 위반으로 실패). 명시적으로 고정해 둔다.
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품'));
DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품'));
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품'));
DELETE FROM drops WHERE name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품');
DELETE FROM users WHERE email LIKE 'k6-wishstorm2-%@loadtest.local';

-- sale_starts_at은 아직 정하지 않는다. 이 트랜잭션은 아래에서 5만 행을 넣어 수 초가 걸릴 수
-- 있는데, now()는 트랜잭션 시작 시각으로 고정되므로 여기서 "now() + 12초"를 미리 정하면
-- 실제 커밋 시점에는 이미 그 여유가 insert 소요 시간만큼 줄어들어 있다(최악엔 이미 지난 시각).
-- 임시로 먼 미래 값을 넣고, 커밋 후 별도의 짧은 트랜잭션에서 실제 현재 시각 기준으로 바꾼다.
INSERT INTO drops (
    seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
    sale_starts_at, sale_ends_at, published_at
)
SELECT s.id, c.id, 'WISH', v.name,
       'GR-170 메일 폭주(스태거) 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
       0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
       now() + interval '1 hour', now() + interval '1 day', now() - interval '1 hour'
FROM sellers s, categories c,
     (VALUES ('k6 메일폭주 대량 상품'), ('k6 메일폭주 소량 상품')) AS v(name)
WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION';

INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 1, TRUE, 0 FROM drops WHERE name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품');

-- 대량 DROP: WISH 5만 명(BCC 500통)
WITH big_users AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    SELECT 'k6-wishstorm2-big-' || gs || '@loadtest.local',
           '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
           'k6wish2b' || gs,
           'USER', 'ACTIVE', 'LOCAL'
    FROM generate_series(1, 50000) AS gs
    RETURNING id
)
INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT bu.id, d.id, now()
FROM big_users bu, drops d
WHERE d.name = 'k6 메일폭주 대량 상품';

-- 소량 DROP: WISH 50명(BCC 1통)
WITH small_users AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    SELECT 'k6-wishstorm2-small-' || gs || '@loadtest.local',
           '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
           'k6wish2s' || gs,
           'USER', 'ACTIVE', 'LOCAL'
    FROM generate_series(1, 50) AS gs
    RETURNING id
)
INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT su.id, d.id, now()
FROM small_users su, drops d
WHERE d.name = 'k6 메일폭주 소량 상품';

COMMIT;

-- 대량/소량 WISH 행이 이미 커밋된 뒤, 여기서부터 실제 현재 시각 기준으로 12초·24초 뒤를 못박는다.
-- 이 트랜잭션은 UPDATE 두 건뿐이라 ms 단위로 끝나, insert 소요 시간이 간격을 갉아먹지 않는다.
BEGIN;
UPDATE drops SET sale_starts_at = now() + interval '12 seconds' WHERE name = 'k6 메일폭주 대량 상품';
UPDATE drops SET sale_starts_at = now() + interval '24 seconds' WHERE name = 'k6 메일폭주 소량 상품';
COMMIT;

SELECT d.id AS drop_id, d.name, d.sale_starts_at, count(w.id) AS wish_count
FROM drops d JOIN wishes w ON w.drop_id = d.id
WHERE d.name IN ('k6 메일폭주 대량 상품', 'k6 메일폭주 소량 상품')
GROUP BY d.id, d.name, d.sale_starts_at
ORDER BY d.sale_starts_at;
