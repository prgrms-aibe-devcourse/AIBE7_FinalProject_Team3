-- 배송 단계 자동 진행 배치(GR-86/GR-172)가 쓰는 부분. 둘 다 stage_updated_at, id 순으로 후보를 골라
-- FOR UPDATE SKIP LOCKED로 선점하지만, 조건과 잠그는 대상 테이블이 다르다.
--   - ShipmentRepository.advanceStageBatch: delivery_stage IN ('COLLECTED','LINE_HAUL_OUT','LINE_HAUL_IN')
--     AND stage_updated_at <= cutoff 조건으로 shipments 행을 잠근다(FOR UPDATE OF s).
--   - ShipmentRepository.lockDeliveryCompletionTargets: delivery_stage = 'OUT_FOR_DELIVERY'
--     AND stage_updated_at <= cutoff 조건으로 orders 행을 잠근다(FOR UPDATE OF o, shipments와 조인).
-- 기존에는 이 조건들을 받쳐주는 인덱스가 없었다.
-- CONCURRENTLY(테이블 쓰기를 막지 않는 온라인 빌드)는 시도했으나, 이 프로젝트의 Flyway(12.4.0) +
-- Testcontainers 조합에서 마이그레이션이 멈추는 현상이 재현돼(순수 psql로는 각 0.7~1.5초에 끝남,
-- 원인 미확정) 적용하지 않았다. 현재 테이블 크기에서는 일반 CREATE INDEX도 짧게 끝나 긴 잠금이
-- 실질적 문제가 되지 않는다 — 테이블이 커지면 재검토한다.
CREATE INDEX idx_shipments_stage_progress
    ON shipments (delivery_stage, stage_updated_at, id);

-- 판매자 주문 목록 조회(GR-174, OrderRepository.findSellerOrders)가 쓰는 부분.
-- ORDER BY created_at DESC, id DESC로 정렬하지만 기존 idx_orders_drop_status(drop_id, status, id)는
-- 정렬을 지원하지 못했다. dropId를 지정한 조회(단일 DROP)만 이 인덱스로 정렬이 커버되고, dropId
-- 없이 판매자 전체 주문을 조회하는 경로는 drop_id 선두 컬럼 때문에 이 인덱스를 못 타 여전히 느리다
-- (GR-174 result.md에 이미 기록된 기존 한계이며, 해결하려면 orders에 seller_id를 비정규화하는 등
-- 더 큰 설계 변경이 필요해 이번 PR 범위 밖이다).
CREATE INDEX idx_orders_drop_created
    ON orders (drop_id, created_at, id);
