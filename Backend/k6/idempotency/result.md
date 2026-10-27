# 멱등성 (idempotency, GR-151)

> 공통 실행 환경·전제는 `../README.md` 참고.

## 2026-10-26 재검증: `cpus: 2`(올바른 t3.nano 스펙) 적용 후에도 통과

`compose.loadtest-nano.yaml`의 `cpus`를 2로 고친 뒤(이전엔 1로 잘못 제한) 다시
실행. **통과** — 체크 13/13 성공, `verify.sql` 기준 idempotency_key당 주문 1건
초과 0행. 메모리 419.5MiB/512MiB(81.93%), OOM-kill 없음. `cpus: 1`일 때(82.0%)와
메모리 사용량은 거의 동일 — 이 시나리오는 애초에 CPU 병목이 없어서 코어 수 변화의
영향이 작았다.

## 2026-10-26 재검증(이전): `memswap_limit` 추가(스왑 차단) 후에도 통과 — 이때는 아직 `cpus: 1`

`memswap_limit: 512m`으로 스왑을 막은 뒤 재실행. **통과** — 체크 13/13 성공,
`verify.sql` 기준 idempotency_key당 주문 1건 초과 0행. 메모리 419.9MiB/512MiB
(82.0%), OOM-kill 없음.

## 2026-10-23 최초 실행 (호스트 스왑 켜진 환경 — `memswap_limit` 추가 전)

동시 동일요청 8건 + 같은 키·다른 본문 1건. **통과** — 체크 13/13 성공, `verify.sql`
기준 idempotency_key당 주문 1건 초과 0행, 실제 생성 주문 2건(동시 요청 1건 + 최초
요청 1건).

- 동시 동일요청: 8개 VU 중 1건만 201, 나머지는 같은 주문을 반환(멱등성 정상)
- 같은 키·다른 본문: 409 `DUPLICATE_IDEMPOTENCY_KEY`

리소스: backend CPU 24.72%, 메모리 479MiB/512MiB(93.56%) — 호스트 스왑이 만든
여유 메모리(최대 1GiB)가 섞인 수치라 `memswap_limit` 추가 후 82.0%로 다시 측정됐다.

## 결론

idempotency_key당 주문 1건 초과 **미발생**. 최신(2026-10-26, 스왑 차단 + 올바른
CPU 2개) 수치는 메모리 81.93%로, 같은 조건에서 재검증한 다른 시나리오들(특히
drop-open-spike, steady-read, connection-pool-exhaustion)이 98~99%대까지 올라간 것과
비교하면 이 시나리오 자체는 메모리 압박이 크지 않았다.
