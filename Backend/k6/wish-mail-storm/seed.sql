-- WISH 판매 알림 메일 폭주 시나리오 전용 더미 데이터(GR-170).
-- common/accounts-seed.sql을 먼저 실행해 k6-seller 계정을 만들어 둬야 한다.
-- DROP 2개(A, B)를 같은 sale_starts_at으로 만들어 DropTransitionScheduler(10초 주기)가
-- 같은 tick에서 둘 다 WISH -> GRAB으로 전환하게 한다. 완료조건의 "동시 전이" 케이스다.
-- 같은 더미 사용자 3000명이 두 DROP을 모두 WISH해서, DROP당 BCC 30통(100명 단위)
-- 씩 총 60통이 noticeMailTaskExecutor(pool=1) 큐에 한 번에 걸린다(MailAsyncConfig).
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/wish-mail-storm/seed.sql
-- 클라이언트 인코딩이 UTF8이 아니면 DROP 이름의 한글 리터럴이 깨져 DELETE/WHERE가
-- 기존 행과 매치되지 않는다(재실행 시 FK 위반으로 실패). 명시적으로 고정해 둔다.
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM wishes
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B'));
DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B'));
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B'));
DELETE FROM drops WHERE name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B');
DELETE FROM users WHERE email LIKE 'k6-wishstorm-%@loadtest.local';

-- 두 DROP을 같은 sale_starts_at(30초 뒤)으로 만든다. measure.ps1의 대기 시간과 맞춰야 한다.
INSERT INTO drops (
    seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
    sale_starts_at, sale_ends_at, published_at
)
SELECT s.id, c.id, 'WISH', v.name,
       'GR-170 메일 폭주 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
       0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
       now() + interval '30 seconds', now() + interval '1 day', now() - interval '1 hour'
FROM sellers s, categories c, (VALUES ('k6 메일폭주 상품 A'), ('k6 메일폭주 상품 B')) AS v(name)
WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION';

INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 1, TRUE, 0 FROM drops WHERE name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B');

-- 더미 WISH 사용자 3000명. 비밀번호는 로그인에 쓰지 않으므로 accounts-seed.sql과 같은 해시를 그대로 둔다.
WITH new_users AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    SELECT 'k6-wishstorm-' || gs || '@loadtest.local',
           '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
           'k6wish' || gs,
           'USER', 'ACTIVE', 'LOCAL'
    FROM generate_series(1, 3000) AS gs
    RETURNING id
)
-- 사용자 전원이 DROP A, B를 모두 WISH한다 -> DROP당 수신자 3000명(=BCC 30통), 총 60통.
INSERT INTO wishes (user_id, drop_id, activated_at)
SELECT nu.id, d.id, now()
FROM new_users nu
CROSS JOIN drops d
WHERE d.name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B');

COMMIT;

-- 확인용. sale_starts_at과 DROP별 WISH 수(= 예상 BCC 통수 * 100에 근접해야 함)를 보여준다.
SELECT d.id AS drop_id, d.name, d.sale_starts_at, count(w.id) AS wish_count
FROM drops d JOIN wishes w ON w.drop_id = d.id
WHERE d.name IN ('k6 메일폭주 상품 A', 'k6 메일폭주 상품 B')
GROUP BY d.id, d.name, d.sale_starts_at
ORDER BY d.name;
