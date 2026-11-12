-- backend micro + DB nano + netem 2.8ms에서 실행한다.
-- 기존 부분 인덱스와 PAYMENT_PENDING/SHIPPED 통합 후보를 한 트랜잭션에서 비교하며,
-- 인덱스 교체와 테스트 데이터는 마지막 ROLLBACK으로 복구한다.
BEGIN;

CREATE TEMP TABLE benchmark_drops (drop_no INT PRIMARY KEY, id BIGINT, seller_id BIGINT) ON COMMIT DROP;
INSERT INTO drops (seller_id, name)
SELECT s.id, 'k6-shipment-index-v35-' || n
FROM sellers s CROSS JOIN generate_series(1, 1000) n
WHERE s.contact_email = 'k6-seller@loadtest.local';
INSERT INTO benchmark_drops
SELECT regexp_replace(name, '^k6-shipment-index-v35-', '')::INT, id, seller_id
FROM drops WHERE name LIKE 'k6-shipment-index-v35-%';

CREATE TEMP TABLE benchmark_write_ids (id BIGINT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO orders (
    order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash, status,
    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
    total_amount, payment_expires_at, created_at
)
SELECT 'GR172-V35-BENCH-' || n, b.id, d.id, d.seller_id,
       'idem-GR172-V35-BENCH-' || n, repeat('a', 64),
       CASE WHEN n <= 16600 THEN 'DELIVERED'
            WHEN n <= 18000 THEN 'SHIPPED'
            WHEN n <= 19000 THEN 'PAID' ELSE 'PAYMENT_PENDING' END,
       '배송 인덱스 측정 상품', 'GRAB 판매자', 10000, 0, 10000,
       CASE WHEN n > 19000 THEN now() - interval '1 minute' ELSE now() + interval '1 day' END,
       CASE WHEN n > 19000 THEN now() - interval '10 minutes' ELSE now() END
FROM generate_series(1, 19500) n
CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
JOIN benchmark_drops d ON d.drop_no = 1 + ((n - 1) % 1000);
INSERT INTO benchmark_write_ids
SELECT id FROM orders
WHERE substring(order_number FROM '[0-9]+$')::INT BETWEEN 18001 AND 19000;

INSERT INTO order_shipping_addresses (order_id, recipient_name, recipient_phone, postal_code, address_line1)
SELECT id, 'k6 테스터', '01099990000', '06236', '배송 인덱스 측정 주소'
FROM orders WHERE order_number LIKE 'GR172-V35-BENCH-%'
  AND id NOT IN (SELECT id FROM benchmark_write_ids);
INSERT INTO shipments (
    order_id, carrier_code, tracking_number, idempotency_key, request_hash,
    shipped_at, delivered_at, delivery_stage, stage_updated_at
)
SELECT o.id, 'CJ대한통운', 'TRACK-GR172-V35-' || n, 'ship-GR172-V35-' || n,
       repeat('b', 64), now() - interval '1 hour',
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes' END,
       CASE WHEN n <= 16600 OR n > 17400 THEN 'OUT_FOR_DELIVERY'
            WHEN n <= 16867 THEN 'COLLECTED'
            WHEN n <= 17134 THEN 'LINE_HAUL_OUT' ELSE 'LINE_HAUL_IN' END,
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes'
            ELSE now() - interval '35 seconds' END
FROM generate_series(1, 18000) n
JOIN orders o ON o.order_number = 'GR172-V35-BENCH-' || n;
ANALYZE orders;
ANALYZE shipments;

\echo '=== 기존 인덱스 크기와 결제 만료 계획'
SELECT pg_size_pretty(pg_relation_size('idx_orders_payment_expiry')) AS index_size;
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT id, payment_expires_at FROM orders
WHERE status = 'PAYMENT_PENDING' AND payment_expires_at <= now()
ORDER BY payment_expires_at, id LIMIT 500;
\echo '=== 기존 배송 단계 전진 계획'
SAVEPOINT base_advance;
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
UPDATE shipments SET delivery_stage = CASE delivery_stage
        WHEN 'COLLECTED' THEN 'LINE_HAUL_OUT' WHEN 'LINE_HAUL_OUT' THEN 'LINE_HAUL_IN'
        WHEN 'LINE_HAUL_IN' THEN 'OUT_FOR_DELIVERY' END,
    stage_updated_at = now(), updated_at = now()
WHERE id IN (
    SELECT s.id FROM shipments s JOIN orders o ON o.id = s.order_id
    WHERE o.status = 'SHIPPED'
      AND s.delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN')
      AND s.stage_updated_at <= now() - interval '30 seconds'
    ORDER BY s.stage_updated_at, s.id LIMIT 500 FOR UPDATE OF s SKIP LOCKED
);
ROLLBACK TO SAVEPOINT base_advance;
\echo '=== 기존 배송 완료 후보 조회 계획'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT s.order_id FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.status = 'SHIPPED' AND s.delivery_stage = 'OUT_FOR_DELIVERY'
  AND s.stage_updated_at <= now() - interval '30 seconds'
ORDER BY s.stage_updated_at, s.id LIMIT 500;

\echo '=== 후보 인덱스로 교체 및 크기'
DROP INDEX idx_orders_payment_expiry;
CREATE INDEX idx_orders_payment_expiry ON orders (status, payment_expires_at, id)
WHERE status IN ('PAYMENT_PENDING', 'SHIPPED');
ANALYZE orders;
SELECT pg_size_pretty(pg_relation_size('idx_orders_payment_expiry')) AS index_size;
\echo '=== 후보 결제 만료 계획'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT id, payment_expires_at FROM orders
WHERE status = 'PAYMENT_PENDING' AND payment_expires_at <= now()
ORDER BY payment_expires_at, id LIMIT 500;
\echo '=== 후보 배송 단계 전진 계획'
SAVEPOINT candidate_advance;
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
UPDATE shipments SET delivery_stage = CASE delivery_stage
        WHEN 'COLLECTED' THEN 'LINE_HAUL_OUT' WHEN 'LINE_HAUL_OUT' THEN 'LINE_HAUL_IN'
        WHEN 'LINE_HAUL_IN' THEN 'OUT_FOR_DELIVERY' END,
    stage_updated_at = now(), updated_at = now()
WHERE id IN (
    SELECT s.id FROM shipments s JOIN orders o ON o.id = s.order_id
    WHERE o.status = 'SHIPPED'
      AND s.delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN')
      AND s.stage_updated_at <= now() - interval '30 seconds'
    ORDER BY s.stage_updated_at, s.id LIMIT 500 FOR UPDATE OF s SKIP LOCKED
);
ROLLBACK TO SAVEPOINT candidate_advance;
\echo '=== 후보 배송 완료 후보 조회 계획'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT s.order_id FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.status = 'SHIPPED' AND s.delivery_stage = 'OUT_FOR_DELIVERY'
  AND s.stage_updated_at <= now() - interval '30 seconds'
ORDER BY s.stage_updated_at, s.id LIMIT 500;

\echo '=== 후보 인덱스: SHIPPED 500건 INSERT / PAID->SHIPPED 1000건 UPDATE (각 3회, ms)'
DO $$ DECLARE started_at timestamptz; elapsed_ms numeric; trial int; start_lsn pg_lsn; wal_bytes numeric; BEGIN
  FOR trial IN 1..3 LOOP
    started_at := clock_timestamp();
    start_lsn := pg_current_wal_insert_lsn();
    INSERT INTO orders (order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash,
      status, product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
      total_amount, payment_expires_at)
    SELECT 'GR172-V35-WRITE-' || trial || '-' || n, b.id, d.id, d.seller_id,
      'idem-GR172-V35-WRITE-' || trial || '-' || n, repeat('c', 64), 'SHIPPED',
      '쓰기 비용 측정 상품', 'GRAB 판매자', 10000, 0, 10000, now() + interval '1 day'
    FROM generate_series(1, 500) n
    CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    JOIN benchmark_drops d ON d.drop_no = 1 + ((n - 1) % 1000);
    elapsed_ms := extract(epoch FROM clock_timestamp() - started_at) * 1000;
    wal_bytes := pg_wal_lsn_diff(pg_current_wal_insert_lsn(), start_lsn);
    RAISE NOTICE '후보 INSERT trial %, % ms, WAL % bytes', trial, round(elapsed_ms, 3), wal_bytes;
    DELETE FROM orders WHERE order_number LIKE 'GR172-V35-WRITE-' || trial || '-%';

    started_at := clock_timestamp();
    start_lsn := pg_current_wal_insert_lsn();
    UPDATE orders SET status = 'SHIPPED', updated_at = now()
    WHERE id IN (SELECT id FROM benchmark_write_ids);
    elapsed_ms := extract(epoch FROM clock_timestamp() - started_at) * 1000;
    wal_bytes := pg_wal_lsn_diff(pg_current_wal_insert_lsn(), start_lsn);
    RAISE NOTICE '후보 UPDATE trial %, % ms, WAL % bytes', trial, round(elapsed_ms, 3), wal_bytes;
    UPDATE orders SET status = 'PAID', updated_at = now()
    WHERE id IN (SELECT id FROM benchmark_write_ids);
  END LOOP;
END $$;

\echo '=== 기존 인덱스로 복구 후 쓰기비용 대조'
DROP INDEX idx_orders_payment_expiry;
CREATE INDEX idx_orders_payment_expiry ON orders (payment_expires_at, id)
WHERE status = 'PAYMENT_PENDING';
ANALYZE orders;
\echo '=== 기존 부분 인덱스 크기 (동일 fixture로 재생성)'
SELECT pg_size_pretty(pg_relation_size('idx_orders_payment_expiry')) AS index_size;
DO $$ DECLARE started_at timestamptz; elapsed_ms numeric; trial int; start_lsn pg_lsn; wal_bytes numeric; BEGIN
  FOR trial IN 1..3 LOOP
    started_at := clock_timestamp();
    start_lsn := pg_current_wal_insert_lsn();
    INSERT INTO orders (order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash,
      status, product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
      total_amount, payment_expires_at)
    SELECT 'GR172-V35-WRITE-' || trial || '-' || n, b.id, d.id, d.seller_id,
      'idem-GR172-V35-WRITE-' || trial || '-' || n, repeat('c', 64), 'SHIPPED',
      '쓰기 비용 측정 상품', 'GRAB 판매자', 10000, 0, 10000, now() + interval '1 day'
    FROM generate_series(1, 500) n
    CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
    JOIN benchmark_drops d ON d.drop_no = 1 + ((n - 1) % 1000);
    elapsed_ms := extract(epoch FROM clock_timestamp() - started_at) * 1000;
    wal_bytes := pg_wal_lsn_diff(pg_current_wal_insert_lsn(), start_lsn);
    RAISE NOTICE '기존 INSERT trial %, % ms, WAL % bytes', trial, round(elapsed_ms, 3), wal_bytes;
    DELETE FROM orders WHERE order_number LIKE 'GR172-V35-WRITE-' || trial || '-%';

    started_at := clock_timestamp();
    start_lsn := pg_current_wal_insert_lsn();
    UPDATE orders SET status = 'SHIPPED', updated_at = now()
    WHERE id IN (SELECT id FROM benchmark_write_ids);
    elapsed_ms := extract(epoch FROM clock_timestamp() - started_at) * 1000;
    wal_bytes := pg_wal_lsn_diff(pg_current_wal_insert_lsn(), start_lsn);
    RAISE NOTICE '기존 UPDATE trial %, % ms, WAL % bytes', trial, round(elapsed_ms, 3), wal_bytes;
    UPDATE orders SET status = 'PAID', updated_at = now()
    WHERE id IN (SELECT id FROM benchmark_write_ids);
  END LOOP;
END $$;

ROLLBACK;
