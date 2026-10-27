# 결제 대기 만료 + 취소 동시성 (expiry, GR-151)

> 공통 실행 환경·전제는 `../README.md` 참고.

## 2026-10-26 재검증: `cpus: 2`(올바른 t3.nano 스펙) 적용 후에도 통과

`compose.loadtest-nano.yaml`의 `cpus`를 2로 고친 뒤(이전엔 1로 잘못 제한) 다시
실행. 이번엔 만료 배치가 취소 요청보다 먼저 처리돼(`status=EXPIRED`,
`release_reason=EXPIRED`) 재고가 정확히 한 번만 반환됨(`verify.sql` 0행) —
**통과**. 메모리 430.4MiB/512MiB(84.06%, 이전 시나리오 누적 효과 포함), OOM-kill 없음.

## 2026-10-26 재검증(이전): `memswap_limit` 추가(스왑 차단) 후에도 통과 — 이때는 아직 `cpus: 1`

`memswap_limit: 512m`으로 스왑을 막은 뒤 재실행. 이번엔 취소 요청이 만료
배치보다 먼저 처리돼(`status=CANCELED`, `release_reason=ORDER_CANCELED`) 이전
실행과 처리 순서는 달랐지만, 재고는 이번에도 정확히 한 번만 반환됨(`verify.sql`
0행) — **통과**. 배치 주기(10초)와 수동 취소 타이밍이 우연히 겹치는 시나리오라
어느 쪽이 먼저 처리되는지는 실행마다 달라질 수 있고, 둘 다 정상 케이스다.

주문 생성 → `payment_expires_at`을 과거로 수동 조정(배치 주기 10초) → 취소 요청.
**통과** — 만료 배치가 먼저 처리(`status=EXPIRED`, `release_reason=EXPIRED`,
`reservation_status=RELEASED`)했고, 뒤따른 취소 요청은 `ORDER_NOT_CANCELABLE`로
거부됐다. 재고는 정확히 한 번만 반환됨(`verify.sql` 0행).

## 결론

재고 이중 복구 **미발생**, 배치·취소 요청 경합에서 재고가 정확히 한 번만 반환됨.
