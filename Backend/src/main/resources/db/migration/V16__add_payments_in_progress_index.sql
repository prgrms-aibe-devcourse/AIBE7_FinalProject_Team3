-- 진행 중 결제 정리 배치(GR-65, ERD.md 인덱스 표)가 쓰는 부분 인덱스.
-- 대상은 status IN (PENDING, UNKNOWN) AND updated_at <= now - 60초이며, updated_at, id 순으로 최대 50건을 FOR UPDATE SKIP LOCKED로 선점한다.
-- 진행 중 결제는 전체 결제 중 일부이므로 해당 행만 색인한다.
CREATE INDEX idx_payments_in_progress
    ON payments (updated_at, id)
    WHERE status IN ('PENDING', 'UNKNOWN');
