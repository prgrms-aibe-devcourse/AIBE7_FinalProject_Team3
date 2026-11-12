-- V35 schema와 20,000건 fixture에서 seller_id 필터만 바꿔 실행계획을 비교한다.
\echo '=== Before: dropId 미지정, orders -> drops JOIN'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.seller_id = 16 ORDER BY o.created_at DESC, o.id DESC LIMIT 20;

\echo '=== After: dropId 미지정, orders.seller_id'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT o.id FROM orders o
WHERE o.seller_id = 16 ORDER BY o.created_at DESC, o.id DESC LIMIT 20;

\echo '=== Before: dropId 지정, orders -> drops JOIN'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.seller_id = 16 AND o.drop_id = 16812
ORDER BY o.created_at DESC, o.id DESC LIMIT 20;

\echo '=== After: dropId 지정, orders.seller_id'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT o.id FROM orders o
WHERE o.seller_id = 16 AND o.drop_id = 16812
ORDER BY o.created_at DESC, o.id DESC LIMIT 20;

\echo '=== Before: dropId 미지정 COUNT'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT COUNT(*) FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.seller_id = 16;

\echo '=== After: dropId 미지정 COUNT'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT COUNT(*) FROM orders o WHERE o.seller_id = 16;

\echo '=== Before: dropId 지정 COUNT'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT COUNT(*) FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.seller_id = 16 AND o.drop_id = 16812;

\echo '=== After: dropId 지정 COUNT'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT COUNT(*) FROM orders o
WHERE o.seller_id = 16 AND o.drop_id = 16812;
