-- V35 스키마·seed-v35-remeasurement.sql 적용 후 실행한다.
-- force_generic_plan으로 JDBC 바인드 변수처럼 NULL을 실행 시점에 전달한다.
SET plan_cache_mode = force_generic_plan;

PREPARE count_before(BIGINT, BIGINT, VARCHAR, VARCHAR) AS
SELECT COUNT(*)
FROM orders o
LEFT JOIN LATERAL (
    SELECT p.status FROM payments p
    WHERE p.order_id = o.id ORDER BY p.id DESC LIMIT 1
) payment ON TRUE
WHERE o.seller_id = $1
  AND (CAST($2 AS BIGINT) IS NULL OR o.drop_id = $2)
  AND (CAST($3 AS VARCHAR) IS NULL OR o.status = $3)
  AND (CAST($4 AS VARCHAR) IS NULL OR payment.status = $4);

PREPARE count_gated(BIGINT, BIGINT, VARCHAR, VARCHAR) AS
SELECT COUNT(*)
FROM orders o
LEFT JOIN LATERAL (
    SELECT p.status FROM payments p
    WHERE p.order_id = o.id ORDER BY p.id DESC LIMIT 1
) payment ON CAST($4 AS VARCHAR) IS NOT NULL
WHERE o.seller_id = $1
  AND (CAST($2 AS BIGINT) IS NULL OR o.drop_id = $2)
  AND (CAST($3 AS VARCHAR) IS NULL OR o.status = $3)
  AND (CAST($4 AS VARCHAR) IS NULL OR payment.status = $4);

\echo '=== Before: paymentStatus NULL에도 LATERAL 실행'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY) EXECUTE count_before(16, NULL, NULL, NULL);
\echo '=== Gated: paymentStatus NULL에서 LATERAL skip'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY) EXECUTE count_gated(16, NULL, NULL, NULL);
\echo '=== 현행 일반 요청: page-first 경로의 countSellerOrders'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT COUNT(*) FROM orders o WHERE o.seller_id = 16;

DEALLOCATE count_before;
DEALLOCATE count_gated;

SET plan_cache_mode = force_custom_plan;
PREPARE count_before_custom(BIGINT, BIGINT, VARCHAR, VARCHAR) AS
SELECT COUNT(*)
FROM orders o
LEFT JOIN LATERAL (
    SELECT p.status FROM payments p
    WHERE p.order_id = o.id ORDER BY p.id DESC LIMIT 1
) payment ON TRUE
WHERE o.seller_id = $1
  AND (CAST($2 AS BIGINT) IS NULL OR o.drop_id = $2)
  AND (CAST($3 AS VARCHAR) IS NULL OR o.status = $3)
  AND (CAST($4 AS VARCHAR) IS NULL OR payment.status = $4);

PREPARE count_gated_custom(BIGINT, BIGINT, VARCHAR, VARCHAR) AS
SELECT COUNT(*)
FROM orders o
LEFT JOIN LATERAL (
    SELECT p.status FROM payments p
    WHERE p.order_id = o.id ORDER BY p.id DESC LIMIT 1
) payment ON CAST($4 AS VARCHAR) IS NOT NULL
WHERE o.seller_id = $1
  AND (CAST($2 AS BIGINT) IS NULL OR o.drop_id = $2)
  AND (CAST($3 AS VARCHAR) IS NULL OR o.status = $3)
  AND (CAST($4 AS VARCHAR) IS NULL OR payment.status = $4);

\echo '=== Before custom plan: paymentStatus NULL'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY) EXECUTE count_before_custom(16, NULL, NULL, NULL);
\echo '=== Gated custom plan: paymentStatus NULL'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY) EXECUTE count_gated_custom(16, NULL, NULL, NULL);

DEALLOCATE count_before_custom;
DEALLOCATE count_gated_custom;
