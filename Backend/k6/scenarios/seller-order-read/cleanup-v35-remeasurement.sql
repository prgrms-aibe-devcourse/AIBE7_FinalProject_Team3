-- seed-v35-remeasurement.sql 전용 테스트 데이터 정리.
BEGIN;
DELETE FROM shipments WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM stock_reservations WHERE order_item_id IN (
    SELECT oi.id FROM order_items oi JOIN drops d ON d.id = oi.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payment_events WHERE payment_id IN (
    SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id
    JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payment_cancellations WHERE payment_id IN (
    SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id
    JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM payments WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM order_cancellations WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM order_items WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM order_shipping_addresses WHERE order_id IN (
    SELECT o.id FROM orders o JOIN drops d ON d.id = o.drop_id WHERE d.name LIKE 'k6-v35-perf-drop-%'
);
DELETE FROM orders WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM wishes WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drop_embeddings WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE name LIKE 'k6-v35-perf-drop-%');
DELETE FROM drops WHERE name LIKE 'k6-v35-perf-drop-%';
DELETE FROM sellers WHERE contact_email = 'k6-v35-perf-seller@loadtest.local';
DELETE FROM users WHERE email = 'k6-v35-perf-seller@loadtest.local'
   OR email LIKE 'k6-v35-wisher-%@loadtest.local';
COMMIT;
