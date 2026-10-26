-- k6 테스트 계정(관리자/판매자/구매자). 모든 시나리오가 공유한다 — 한 번만 실행하면 된다.
-- DROP/옵션처럼 시나리오마다 모양이 다른 데이터는 각 시나리오 폴더의 seed.sql에서 따로 만든다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/accounts-seed.sql
-- 비밀번호(전부 동일): LoadTest1234!
-- 해시는 SecurityConfig.passwordEncoder()와 동일한 파라미터(Argon2id, m=19456, t=2, p=1)로 생성했다.
-- 이미 만들어진 계정이 있으면 건드리지 않는다(DELETE하지 않음) — 계정에 주문이 쌓여있어도
-- buyer_id FK 때문에 삭제가 막히므로, 재실행 가능하게 하려고 삭제 대신 ON CONFLICT로 건너뛴다.

WITH admin_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-admin@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6admin', 'ADMIN', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
), seller_user AS (
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-seller@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6seller', 'USER', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
), buyer_user AS (
    -- k6 로그인에 쓰는 계정. 여러 시나리오가 이 계정 하나로 Idempotency-Key만 다르게
    -- 동시 주문을 여러 건 보낸다(orders 테이블은 buyer+idempotency_key만 유니크라 가능).
    INSERT INTO users (email, password_hash, nickname, role, status, provider)
    VALUES (
        'k6-buyer@loadtest.local',
        '$argon2id$v=19$m=19456,t=2,p=1$mQf+evWSiCl4fnAwSr1uuw$oa1Bme/rIecpeLnD3fs9NPR5U21R8EY6RqKqxUdy3WU',
        'k6buyer', 'USER', 'ACTIVE', 'LOCAL'
    )
    ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
    RETURNING id
)
INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
SELECT seller_user.id, 'k6 부하테스트 브랜드', 'k6-seller@loadtest.local', 'APPROVED',
       admin_user.id, now()
FROM seller_user, admin_user
ON CONFLICT (user_id) DO NOTHING;

-- 확인용.
SELECT u.email, u.role, s.id AS seller_id, s.status AS seller_status
FROM users u
LEFT JOIN sellers s ON s.user_id = u.id
WHERE u.email IN ('k6-admin@loadtest.local', 'k6-seller@loadtest.local', 'k6-buyer@loadtest.local')
ORDER BY u.email;
