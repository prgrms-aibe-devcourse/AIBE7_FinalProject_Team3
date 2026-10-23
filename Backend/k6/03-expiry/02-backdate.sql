-- 결제 대기 만료 + 취소 동시 발생 시나리오 2/3.
-- PAYMENT_WAIT_MINUTES가 10분 고정(OrderCreateTransactionService)이라 실제로 10분을
-- 기다리는 대신, 방금 만든 주문의 payment_expires_at을 직접 과거로 당겨 다음 배치
-- 틱(10초 주기, PaymentBatchScheduler)에서 바로 만료 대상이 되게 한다.
--
-- 사용 (01-create-order.js 출력의 ORDER_ID를 그대로 넣는다):
--   docker compose exec -T postgres psql -U grab -d grab \
--     -v order_id="'<ORDER_ID>'" -f - < Backend/k6/03-expiry/02-backdate.sql
-- ck_orders_payment_expiry 제약(payment_expires_at > created_at)이 있어 created_at도
-- 같이 당긴다. order_items/stock_reservations의 created_at은 이 제약과 무관해 안 건드려도 된다.
--
-- 주의: 이 3단계(01→02→03)는 취소 요청과 배치 틱의 타이밍이 우연히 겹치길 바라는
-- 수동 스모크 테스트다. 만료 처리와 취소 처리가 같은 주문을 정말 동시에(같은 트랜잭션
-- 타이밍에) 잠그러 들 때도 안전한지는 이걸로 보장되지 않는다 — 그건 DB 잠금 순서를
-- 코드로 제어하는 백엔드 통합테스트(OrderCancelTransactionService/PaymentBatchService)의
-- 몫이고, 이 PR(k6 스크립트) 범위 밖이다.
UPDATE orders
SET created_at = now() - interval '2 minutes',
    payment_expires_at = now() - interval '1 minute'
WHERE public_id = :order_id
  AND status = 'PAYMENT_PENDING'
RETURNING id, order_number, status, payment_expires_at;
