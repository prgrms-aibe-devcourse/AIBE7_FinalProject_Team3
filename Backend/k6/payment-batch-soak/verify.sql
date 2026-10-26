-- GR-154 검증: 장시간 배치가 돌아도 재고 정합성은 깨지면 안 된다(초과판매·음수재고 0행 기대).
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/payment-batch-soak/verify.sql

SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity,
       o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity AS remaining
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 결제배치 롱런 상품'
  AND (o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
       OR o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity);

-- 참고용: 배치가 실제로 상태를 얼마나 굴렸는지(만료·취소·결제완료 건수).
SELECT o.status, COUNT(*) AS order_count
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 결제배치 롱런 상품'
GROUP BY o.status
ORDER BY o.status;

-- 참고용: PAYMENT_PENDING인데 결제 마감이 지난 주문이 남아 있으면 배치가 밀린 것이다(0행 기대).
SELECT COUNT(*) AS stuck_past_deadline
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 결제배치 롱런 상품'
  AND o.status = 'PAYMENT_PENDING'
  AND o.payment_expires_at < now() - interval '1 minute';

-- 참고용: 결과 불명(UNKNOWN)으로 남아 보정 대상인 결제가 있으면 확인한다.
SELECT COUNT(*) AS unresolved_reconciliation
FROM payments p
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 결제배치 롱런 상품'
  AND p.reconciliation_status = 'REQUIRED';
