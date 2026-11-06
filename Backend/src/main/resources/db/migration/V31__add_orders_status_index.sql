-- GR-191 재측정(2026-11-06)에서 발견: 배송 단계 배치(advanceStageBatch,
-- lockDeliveryCompletionTargets)가 공통으로 쓰는 `orders.status = 'SHIPPED'` 필터를
-- 받쳐주는 인덱스가 없다. idx_orders_drop_status(drop_id, status, id)는 drop_id가
-- 선두 컬럼이라 드롭 조건 없이 상태만으로 거르는 이 쿼리들은 못 타고, 그 결과 Seq Scan이
-- 됐다(전체 개선폭이 25~30% 기대치보다 작은 약 9%에 그친 원인, shipment-progress-soak
-- result.md 참고). idx_orders_payment_expiry(status, payment_expires_at, id)를 얹어
-- 쓸 수도 있지만 그건 결제 대기 만료 배치 전용으로 만든 인덱스라 목적이 다르고, 재사용은
-- 플래너의 선택적 판단(통계 경계선)에 기대는 거라 안정적이지 않다.
CREATE INDEX idx_orders_status_id
    ON orders (status, id);
