-- 배송 배치 V34 스키마 기준 실행 계획 측정용 임시 fixture.
-- DB 제한 환경에서 실행: .../compose.loadtest-micro.yaml + .../compose.loadtest-db-nano.yaml
-- 주문 18,000건(DELIVERED 16,600 / SHIPPED 1,400), shipments 18,000건을
-- 1,000개 DROP에 분산한다. 측정용 UPDATE와 fixture는 모두 ROLLBACK한다.
BEGIN;

CREATE TEMP TABLE benchmark_drops (drop_no INT PRIMARY KEY, id BIGINT NOT NULL) ON COMMIT DROP;
INSERT INTO drops (seller_id, name)
SELECT s.id, 'k6-shipment-batch-v34-' || n
FROM sellers s
CROSS JOIN generate_series(1, 1000) n
WHERE s.contact_email = 'k6-seller@loadtest.local';
INSERT INTO benchmark_drops (drop_no, id)
SELECT regexp_replace(name, '^k6-shipment-batch-v34-', '')::INT, id
FROM drops WHERE name LIKE 'k6-shipment-batch-v34-%';

INSERT INTO orders (
    order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash, status,
    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
    total_amount, payment_expires_at
)
SELECT 'GR172-V34-BENCH-' || n, b.id, d.id, drops.seller_id,
       'idem-GR172-V34-BENCH-' || n, repeat('a', 64),
       CASE WHEN n <= 16600 THEN 'DELIVERED' ELSE 'SHIPPED' END,
       '배송 배치 측정 상품', 'GRAB 판매자', 10000, 0, 10000, now() + interval '1 day'
FROM generate_series(1, 18000) n
CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
JOIN benchmark_drops d ON d.drop_no = 1 + ((n - 1) % 1000)
JOIN drops ON drops.id = d.id;

INSERT INTO order_shipping_addresses (order_id, recipient_name, recipient_phone, postal_code, address_line1)
SELECT id, 'k6 테스터', '01099990000', '06236', '배송 배치 측정 주소'
FROM orders WHERE order_number LIKE 'GR172-V34-BENCH-%';

INSERT INTO shipments (
    order_id, carrier_code, tracking_number, idempotency_key, request_hash,
    shipped_at, delivered_at, delivery_stage, stage_updated_at
)
SELECT o.id, 'CJ대한통운', 'TRACK-GR172-V34-' || n, 'ship-GR172-V34-' || n,
       repeat('b', 64), now() - interval '1 hour',
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes' END,
       CASE WHEN n <= 16600 OR n > 17400 THEN 'OUT_FOR_DELIVERY'
            WHEN n <= 16867 THEN 'COLLECTED'
            WHEN n <= 17134 THEN 'LINE_HAUL_OUT'
            ELSE 'LINE_HAUL_IN' END,
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes'
            ELSE now() - interval '35 seconds' END
FROM generate_series(1, 18000) n
JOIN orders o ON o.order_number = 'GR172-V34-BENCH-' || n;

ANALYZE orders;
ANALYZE shipments;

SELECT status, count(*) FROM orders GROUP BY status ORDER BY status;
SELECT delivery_stage, count(*) FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.order_number LIKE 'GR172-V34-BENCH-%' GROUP BY delivery_stage ORDER BY delivery_stage;

SAVEPOINT advance_stage;
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
UPDATE shipments
SET delivery_stage = CASE delivery_stage
        WHEN 'COLLECTED' THEN 'LINE_HAUL_OUT'
        WHEN 'LINE_HAUL_OUT' THEN 'LINE_HAUL_IN'
        WHEN 'LINE_HAUL_IN' THEN 'OUT_FOR_DELIVERY'
    END,
    stage_updated_at = now(), updated_at = now()
WHERE id IN (
    SELECT s.id FROM shipments s JOIN orders o ON o.id = s.order_id
    WHERE o.status = 'SHIPPED'
      AND s.delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN')
      AND s.stage_updated_at <= now() - interval '30 seconds'
    ORDER BY s.stage_updated_at, s.id
    LIMIT 500 FOR UPDATE OF s SKIP LOCKED
);
ROLLBACK TO SAVEPOINT advance_stage;
RELEASE SAVEPOINT advance_stage;

SAVEPOINT complete_delivery;
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT s.order_id FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.status = 'SHIPPED' AND s.delivery_stage = 'OUT_FOR_DELIVERY'
  AND s.stage_updated_at <= now() - interval '30 seconds'
ORDER BY s.stage_updated_at, s.id
LIMIT 500 FOR UPDATE OF o SKIP LOCKED;
ROLLBACK TO SAVEPOINT complete_delivery;
RELEASE SAVEPOINT complete_delivery;

ROLLBACK;
