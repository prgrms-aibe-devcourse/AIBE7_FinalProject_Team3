-- GR-211: 회원 ID 변환 캐시 재측정용 계정 800개(k6-idlookup-001~800@loadtest.local).
-- 캐시를 넣은 뒤 계정 1개(k6-buyer)로 재면 모든 요청이 캐시에 적중해 효과가 부풀려진다. VU마다 다른 계정을 쓰도록
-- 최대 VU(800)만큼 만든다. concurrency.js에 ACCOUNTS=<수>를 주면 001부터 그 수만큼 로그인한다.
-- 비밀번호는 공통 계정과 같다(LoadTest1234!, Argon2id m=19456 t=2 p=1). 주문 등 다른 데이터는 넣지 않는다(내 정보 조회만 잰다).
-- 이미 있는 계정은 건드리지 않는다(재실행 안전). 공통 계정 시드(k6/common/accounts-seed.sql)와 순서는 상관없다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/user-id-lookup/accounts-seed.sql
INSERT INTO users (email, password_hash, nickname, role, status, provider)
SELECT 'k6-idlookup-' || lpad(gs::text, 3, '0') || '@loadtest.local',
       '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
       'k6idlookup' || lpad(gs::text, 3, '0'), 'USER', 'ACTIVE', 'LOCAL'
FROM generate_series(1, 800) gs
ON CONFLICT (email) DO NOTHING;

-- 측정 전 기록용. 800이 아니면 일부 계정이 없는 것이다.
SELECT count(*) AS idlookup_accounts
FROM users
WHERE email LIKE 'k6-idlookup-___@loadtest.local' AND status = 'ACTIVE';
