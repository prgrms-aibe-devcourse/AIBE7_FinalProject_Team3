-- GR-172: 배송 진행 배치 롱런(soak) 시나리오 초기 데이터. ShipmentProgressScheduler가
-- 처리할 "배송 중 주문"을 깔아둔다. 이 배치는 HTTP 트리거 없이 순수 DB 상태(시간 경과)만
-- 보고 도는 배치라(README 참고) 재고·order_items 없이 orders+shipments만 직접 꽂는다
-- (ShipmentProgressServiceIntegrationTest와 같은 최소 구성).
-- 초기 40건은 이미 단계 간격(기본 30초, grab.mock-delivery.stage-interval)보다 오래돼
-- 있어 scheduler 첫 주기에 바로 전진 대상이 된다. soak가 이어지는 동안의 지속적인 유입은
-- feed-shipments.sh가 맡는다.
-- 사전 준비: common/accounts-seed.sql 실행 후. 재실행 가능하도록 기존 데이터부터 지운다.
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/shipment-progress-soak/seed.sql
SET client_encoding = 'UTF8';
BEGIN;

DELETE FROM shipments
    WHERE order_id IN (
        SELECT o.id FROM orders o
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name = 'k6 배송배치 롱런 상품'
    );
DELETE FROM orders
    WHERE drop_id IN (SELECT id FROM drops WHERE name = 'k6 배송배치 롱런 상품');
DELETE FROM drops WHERE name = 'k6 배송배치 롱런 상품';

INSERT INTO drops (
    seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
    sale_starts_at, sale_ends_at, published_at, grab_started_at
)
SELECT s.id, c.id, 'GRAB',
       'k6 배송배치 롱런 상품', 'GR-172 검증용 더미 상품입니다. 실제 판매 상품이 아닙니다.',
       0, '부하테스트용 더미 데이터이며 실제 배송은 없습니다.',
       now() - interval '1 day', now() + interval '1 day', now() - interval '1 day', now() - interval '1 hour'
FROM sellers s, categories c
WHERE s.contact_email = 'k6-seller@loadtest.local' AND c.code = 'FASHION';

INSERT INTO orders (
    order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
    payment_expires_at, paid_at
)
SELECT 'GR172-SEED-' || gs, b.id, d.id, 'idem-GR172-seed-' || gs,
       substr(md5('GR172-seed-' || gs) || md5('GR172-seed2-' || gs), 1, 64), 'SHIPPED',
       'k6 배송배치 롱런 상품', 'GRAB 판매자', 10000, 0, 10000,
       'k6 테스터', '01099990000', '06236', '서울시 강남구 테스트로 1',
       now() + interval '15 minutes', now() - interval '3 hours'
FROM generate_series(1, 40) gs,
     (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b,
     (SELECT id FROM drops WHERE name = 'k6 배송배치 롱런 상품') d;

INSERT INTO shipments (
    order_id, carrier_code, tracking_number, idempotency_key, request_hash,
    shipped_at, delivery_stage, stage_updated_at
)
SELECT o.id, 'CJ대한통운', 'TRACK-GR172-SEED-' || o.id, 'ship-idem-GR172-seed-' || o.id,
       substr(md5('GR172-ship-' || o.id) || md5('GR172-ship2-' || o.id), 1, 64),
       now() - interval '3 hours', 'COLLECTED', now() - interval '35 seconds'
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품' AND o.order_number LIKE 'GR172-SEED-%';

COMMIT;

SELECT d.id AS drop_id, count(*) AS seeded_orders
FROM drops d JOIN orders o ON o.drop_id = d.id
WHERE d.name = 'k6 배송배치 롱런 상품'
GROUP BY d.id;
