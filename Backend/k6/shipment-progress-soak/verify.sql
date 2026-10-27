-- GR-172 검증: 장시간 배치가 돌아도 배송 단계 전이가 중복되거나 유실되면 안 된다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < k6/shipment-progress-soak/verify.sql

-- 1) 밀린 주문: 단계 간격(기본 30초)의 몇 배가 지나도록 COLLECTED~LINE_HAUL_IN에 멈춰
-- 있으면 배치가 못 따라간 것이다(0행 기대. 폴링 주기(기본 10초) 오차를 흡수하도록 여유를 둠).
SELECT s.order_id, s.delivery_stage, s.stage_updated_at, now() - s.stage_updated_at AS age
FROM shipments s
JOIN orders o ON o.id = s.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품'
  AND o.status = 'SHIPPED'
  AND s.delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN')
  AND s.stage_updated_at < now() - interval '2 minutes';

-- 2) OUT_FOR_DELIVERY에서 멈춘 채 DELIVERED로 안 넘어간 주문(배송완료 배치 유실 의심, 0행 기대).
SELECT s.order_id, s.stage_updated_at
FROM shipments s
JOIN orders o ON o.id = s.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품'
  AND o.status = 'SHIPPED'
  AND s.delivery_stage = 'OUT_FOR_DELIVERY'
  AND s.stage_updated_at < now() - interval '2 minutes';

-- 3) orders.status(DELIVERED)와 shipments.delivered_at 설정 여부 불일치(중복·부분 처리 의심, 0행 기대).
SELECT o.id AS order_id, o.status, s.delivery_stage, s.delivered_at
FROM orders o
JOIN shipments s ON s.order_id = o.id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품'
  AND ((o.status = 'DELIVERED') <> (s.delivered_at IS NOT NULL));

-- 4) 참고용: 현재 단계별·상태별 분포(배치가 파이프라인을 고르게 흘려보내는지).
SELECT s.delivery_stage, o.status, count(*) AS order_count
FROM shipments s
JOIN orders o ON o.id = s.order_id
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품'
GROUP BY s.delivery_stage, o.status
ORDER BY s.delivery_stage, o.status;

-- 5) 참고용: 전체 투입량 대비 완료량(배치가 밀리지 않고 꾸준히 완료까지 처리하는지).
SELECT count(*) AS total_seeded,
       count(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered
FROM orders o
JOIN drops d ON d.id = o.drop_id
WHERE d.name = 'k6 배송배치 롱런 상품';
