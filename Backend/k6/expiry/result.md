# 결제 대기 만료 + 취소 동시성 (expiry, GR-151)

> 공통 실행 환경·전제는 `../README.md` 참고.

주문 생성 → `payment_expires_at`을 과거로 수동 조정(배치 주기 10초) → 취소 요청.
**통과** — 만료 배치가 먼저 처리(`status=EXPIRED`, `release_reason=EXPIRED`,
`reservation_status=RELEASED`)했고, 뒤따른 취소 요청은 `ORDER_NOT_CANCELABLE`로
거부됐다. 재고는 정확히 한 번만 반환됨(`verify.sql` 0행).

## 결론

재고 이중 복구 **미발생**, 배치·취소 요청 경합에서 재고가 정확히 한 번만 반환됨.
