-- 결제 취소 재요청 배치(GR-87, ERD.md 3.3)는 진행 중인 취소를 next_retry_at <= now 조건으로 고른다.
-- 이제 REQUESTED도 생성할 때 next_retry_at = 생성 시각 + 1분을 넣으므로, 이전에 next_retry_at 없이 저장된 REQUESTED에 같은 값을 채운다.
-- UNKNOWN은 결과를 반영할 때 이미 값을 넣었다. 끝난 취소(SUCCEEDED·FAILED)는 대상이 아니므로 비워 둔다.
UPDATE payment_cancellations
SET next_retry_at = created_at + INTERVAL '1 minute'
WHERE status = 'REQUESTED'
  AND next_retry_at IS NULL;
