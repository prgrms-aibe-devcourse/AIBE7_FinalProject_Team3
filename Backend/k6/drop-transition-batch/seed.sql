-- GR-171 기준선: WISH 없는 DROP 600개(BATCH_SIZE=500보다 많아 drain()이 내부적으로
-- 2회(500+100) 왕복하게 만든다)를 같은 sale_starts_at으로 동시 전이 대상에 올린다.
-- 메일 발송 트리거(WishSaleNoticeMailListener) 없이 순수 DB 배치 처리 시간만 본다.
-- WISH를 포함한 비교군은 seed-with-wish.sql.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/drop-transition-batch/seed.sql
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM drop_notifications
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배치전이 기준');
DELETE FROM drop_options
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배치전이 기준');
DELETE FROM drops WHERE name = 'k6 배치전이 기준';

-- sale_starts_at은 아직 정하지 않는다. 600행 insert가 끝난 뒤 별도 트랜잭션에서
-- 실제 현재 시각 기준으로 못박는다(insert 소요 시간이 전이 타이밍을 갉아먹지 않게).
WITH drop_rows AS (
    INSERT INTO drops (
        seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
        sale_starts_at, sale_ends_at, published_at
    )
    SELECT s.id, c.id, 'WISH', 'k6 배치전이 기준',
           'GR-171 대량 동시 전이 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
           0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
           now() + interval '1 hour', now() + interval '1 day', now() - interval '1 hour'
    FROM sellers s, categories c, generate_series(1, 600) AS gs
    WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION'
    RETURNING id
)
INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
SELECT id, 10000, 1, TRUE, 0 FROM drop_rows;

COMMIT;

BEGIN;
UPDATE drops SET sale_starts_at = now() + interval '20 seconds' WHERE name = 'k6 배치전이 기준';
COMMIT;

SELECT count(*) AS drop_count, min(sale_starts_at), max(sale_starts_at)
FROM drops WHERE name = 'k6 배치전이 기준';
