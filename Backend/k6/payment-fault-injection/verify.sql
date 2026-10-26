-- GR-155 검증: PG 장애(승인 거절·결과불명, 취소 결과불명)가 섞여도 재고 정합성이 깨지면 안 되고,
-- 결과불명은 배치가 결국 해소해야 하며, 보정 환불 기록이 중복 생성되면 안 된다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/payment-fault-injection/verify.sql

-- 1) 재고 정합성(초과판매·음수재고 0행 기대).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
  AND (o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
       OR o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity);

-- 참고용: 주문 상태 분포.
SELECT o.status, COUNT(*) AS order_count
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
GROUP BY o.status
ORDER BY o.status;

-- 참고용: 결제 시도 상태 분포(장애 주입으로 FAILED·UNKNOWN도 정상적으로 섞여 있어야 한다).
SELECT p.status, COUNT(*) AS payment_count
FROM payments p
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
GROUP BY p.status
ORDER BY p.status;

-- 2) MockPaymentGateway의 resolveDelaySeconds(기본 20초)보다 충분히 지난 결제가 여전히
--    PENDING·UNKNOWN으로 남아 있으면 배치가 결과불명을 못 풀어낸 것이다(0행 기대. 여유를 두고 2분 기준).
SELECT COUNT(*) AS stuck_in_progress_payments
FROM payments p
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
  AND p.status IN ('PENDING', 'UNKNOWN')
  AND p.created_at < now() - interval '2 minutes';

-- 3) 취소 재요청도 마찬가지로 오래 REQUESTED·UNKNOWN으로 남아 있으면 안 된다(0행 기대).
SELECT COUNT(*) AS stuck_in_progress_cancellations
FROM payment_cancellations pc
JOIN payments p ON p.id = pc.payment_id
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
  AND pc.status IN ('REQUESTED', 'UNKNOWN')
  AND pc.created_at < now() - interval '2 minutes';

-- 3-1) 생성 시각과 무관하게 테스트 종료 시점에 미해결로 남은 취소 전체 건수(참고용 스냅샷).
--      stuck_in_progress_cancellations(2분 경과분)와 달리, 방금 생성돼 아직 2분이 안 지난
--      미해결 건까지 포함한 전체 규모를 보기 위함.
SELECT COUNT(*) AS unresolved_cancellations_total
FROM payment_cancellations pc
JOIN payments p ON p.id = pc.payment_id
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
  AND pc.status IN ('REQUESTED', 'UNKNOWN');

-- 4) 결제당 보정 환불 기록은 하나뿐이어야 한다(저장된 기록만 센다. uq_payment_cancellations_compensation이
--    DB 레벨에서 막아주므로 중복 INSERT 시도 자체는 여기 남지 않고 거부된다 — 시도 횟수까지 보려면
--    애플리케이션 로그나 제약 위반 메트릭을 별도로 봐야 한다. 0행 기대).
SELECT pc.payment_id, COUNT(*) AS compensation_count
FROM payment_cancellations pc
WHERE pc.purpose = 'LATE_APPROVAL_COMPENSATION'
  AND pc.payment_id IN (
      SELECT p.id FROM payments p
      JOIN orders o ON o.id = p.order_id
      JOIN drops d ON d.id = o.drop_id
      WHERE d.name = 'k6 PG장애주입 상품'
  )
GROUP BY pc.payment_id
HAVING COUNT(*) > 1;

-- 참고용: 보정 대상(REQUIRED)으로 남아 있는 결제 수.
SELECT COUNT(*) AS unresolved_reconciliation
FROM payments p
JOIN orders o ON o.id = p.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 PG장애주입 상품'
  AND p.reconciliation_status = 'REQUIRED';
