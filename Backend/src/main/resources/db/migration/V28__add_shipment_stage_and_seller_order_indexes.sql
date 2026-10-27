-- 배송 단계 자동 진행 배치(GR-86/GR-172)가 쓰는 부분. ShipmentRepository.advanceStageBatch,
-- lockDeliveryCompletionTargets 모두 delivery_stage IN (...) AND stage_updated_at <= cutoff 조건으로
-- stage_updated_at, id 순으로 후보를 골라 FOR UPDATE SKIP LOCKED로 선점한다. 기존에는 받쳐주는 인덱스가 없었다.
CREATE INDEX idx_shipments_stage_progress
    ON shipments (delivery_stage, stage_updated_at, id);

-- 판매자 주문 목록 조회(GR-174, OrderRepository.findSellerOrders)가 쓰는 부분.
-- ORDER BY created_at DESC, id DESC로 정렬하지만 기존 idx_orders_drop_status(drop_id, status, id)는
-- 정렬을 지원하지 못했다.
CREATE INDEX idx_orders_drop_created
    ON orders (drop_id, created_at, id);
