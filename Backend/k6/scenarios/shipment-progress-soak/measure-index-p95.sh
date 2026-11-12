#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
PHASE="${1:?usage: measure-index-p95.sh baseline|candidate}"
[[ "$PHASE" == "baseline" || "$PHASE" == "candidate" ]]

BACKEND=$(docker compose ps -q backend)
PG=$(docker compose ps -q postgres)
[[ "$(docker inspect -f '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}' "$BACKEND")" == "1073741824 2000000000" ]] || { echo 'backend micro 제한이 필요하다' >&2; exit 1; }
[[ "$(docker inspect -f '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}' "$PG")" == "536870912 1000000000" ]] || { echo 'PostgreSQL nano 제한이 필요하다' >&2; exit 1; }
[[ "$(bash k6/scenarios/drop-detail-scale/netem.sh show)" == *'delay 2.8ms'* ]] || { echo 'RTT 2.8ms netem 설정이 필요하다' >&2; exit 1; }

NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$PG")
PASSWORD=$(docker compose exec -T postgres printenv POSTGRES_PASSWORD)

{
cat <<'SQL'
BEGIN;
CREATE TEMP TABLE p95_drops (drop_no INT PRIMARY KEY, id BIGINT, seller_id BIGINT) ON COMMIT DROP;
INSERT INTO drops (seller_id, name)
SELECT s.id, 'k6-shipment-p95-v35-' || n
FROM sellers s CROSS JOIN generate_series(1, 1000) n
WHERE s.contact_email = 'k6-seller@loadtest.local';
INSERT INTO p95_drops
SELECT regexp_replace(name, '^k6-shipment-p95-v35-', '')::INT, id, seller_id
FROM drops WHERE name LIKE 'k6-shipment-p95-v35-%';
INSERT INTO orders (
    order_number, buyer_id, drop_id, seller_id, idempotency_key, request_hash, status,
    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
    total_amount, payment_expires_at, created_at
)
SELECT 'GR172-V35-P95-' || n, b.id, d.id, d.seller_id,
       'idem-GR172-V35-P95-' || n, repeat('a', 64),
       CASE WHEN n <= 16600 THEN 'DELIVERED'
            WHEN n <= 18000 THEN 'SHIPPED'
            WHEN n <= 19000 THEN 'PAID' ELSE 'PAYMENT_PENDING' END,
       '배송 p95 측정 상품', 'GRAB 판매자', 10000, 0, 10000,
       CASE WHEN n > 19000 THEN now() - interval '1 minute' ELSE now() + interval '1 day' END,
       CASE WHEN n > 19000 THEN now() - interval '10 minutes' ELSE now() END
FROM generate_series(1, 19500) n
CROSS JOIN (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b
JOIN p95_drops d ON d.drop_no = 1 + ((n - 1) % 1000);
INSERT INTO order_shipping_addresses (order_id, recipient_name, recipient_phone, postal_code, address_line1)
SELECT id, 'k6 테스터', '01099990000', '06236', '배송 p95 측정 주소'
FROM orders WHERE order_number LIKE 'GR172-V35-P95-%'
  AND substring(order_number FROM '[0-9]+$')::INT NOT BETWEEN 18001 AND 19000;
INSERT INTO shipments (
    order_id, carrier_code, tracking_number, idempotency_key, request_hash,
    shipped_at, delivered_at, delivery_stage, stage_updated_at
)
SELECT o.id, 'CJ대한통운', 'TRACK-GR172-V35-P95-' || n, 'ship-GR172-V35-P95-' || n,
       repeat('b', 64), now() - interval '1 hour',
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes' END,
       CASE WHEN n <= 16600 OR n > 17400 THEN 'OUT_FOR_DELIVERY'
            WHEN n <= 16867 THEN 'COLLECTED'
            WHEN n <= 17134 THEN 'LINE_HAUL_OUT' ELSE 'LINE_HAUL_IN' END,
       CASE WHEN n <= 16600 THEN now() - interval '10 minutes'
            ELSE now() - interval '35 seconds' END
FROM generate_series(1, 18000) n
JOIN orders o ON o.order_number = 'GR172-V35-P95-' || n;
ANALYZE orders;
ANALYZE shipments;

DROP INDEX idx_orders_payment_expiry;
CREATE INDEX idx_orders_payment_expiry ON orders (payment_expires_at, id)
WHERE status = 'PAYMENT_PENDING';
ANALYZE orders;
SQL

emit_trial() {
    local phase="$1" query="$2" trial="$3"
    if [[ "$trial" == "warm1" ]]; then echo "\\echo P95:${PHASE}:${query}"; fi
    echo "SAVEPOINT ${phase}_${query}_${trial};"
    if [[ "$query" == "advance" ]]; then
        cat <<'SQL'
\timing on
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
    ORDER BY s.stage_updated_at, s.id LIMIT 500 FOR UPDATE OF s SKIP LOCKED
);
\timing off
SQL
    else
        cat <<'SQL'
\o /dev/null
\timing on
SELECT s.order_id FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.status = 'SHIPPED' AND s.delivery_stage = 'OUT_FOR_DELIVERY'
  AND s.stage_updated_at <= now() - interval '30 seconds'
ORDER BY s.stage_updated_at, s.id LIMIT 500 FOR UPDATE OF o SKIP LOCKED;
\timing off
\o
SQL
    fi
    echo "ROLLBACK TO SAVEPOINT ${phase}_${query}_${trial};"
}

if [[ "$PHASE" == "baseline" ]]; then
    cat <<'SQL'
DROP INDEX idx_orders_payment_expiry;
CREATE INDEX idx_orders_payment_expiry ON orders (payment_expires_at, id)
WHERE status = 'PAYMENT_PENDING';
ANALYZE orders;
SQL
fi
for query in advance completion; do
    for trial in $(seq 1 5); do emit_trial "$PHASE" "$query" "warm${trial}"; done
    for trial in $(seq 1 40); do emit_trial "$PHASE" "$query" "$trial"; done
done
echo ROLLBACK
} | docker run --rm -i --network "$NETWORK" -e PGPASSWORD="$PASSWORD" postgres:18 \
    psql -X -qAt -v ON_ERROR_STOP=1 -h postgres -U grab -d grab 2>&1 \
  | awk '
      /^P95:/ { split($0, p, ":"); key = p[2] "/" p[3]; seen[key] = 0; next }
      /^Time: [0-9.]+ ms$/ {
        seen[key]++
        if (seen[key] > 5) {
          value = $2 + 0
          count[key]++
          times[key, count[key]] = value
        }
      }
      END {
        for (key in count) {
          n = count[key]
          for (i = 2; i <= n; i++) {
            item = times[key, i]; j = i - 1
            while (j >= 1 && times[key, j] > item) {
              times[key, j + 1] = times[key, j]; j--
            }
            times[key, j + 1] = item
          }
          p50 = int(n * 0.50 + 0.999999)
          p95 = int(n * 0.95 + 0.999999)
          printf "%s n=%d p50=%.3fms p95=%.3fms max=%.3fms\n", key, n, times[key, p50], times[key, p95], times[key, n]
        }
      }'

docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab \
    -c "VACUUM (ANALYZE) orders, shipments" \
    -c "REINDEX INDEX idx_orders_payment_expiry"
