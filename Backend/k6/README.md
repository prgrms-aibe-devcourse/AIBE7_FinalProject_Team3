# k6 부하테스트 (GR-150)

로컬 백엔드(`localhost:8080`)만 대상으로 한다. 운영/외부 서비스로는 쏘지 않는다.

> 각 시나리오 결과는 **운영 캐파시티 수치가 아니라 하한선 검증**이다. 목표는
> 운영(t3.small, 2vCPU/2GB)보다 약한 t3.nano(1vCPU/0.5GB) 환경에서도 재고
> 동시성이 깨지지 않는다는 것을 확인하는 것이고, 정확한 RPS·응답시간을
> 측정하는 것이 아니다. nano 제한 환경으로 돌릴 때는 `docker compose -f
> compose.yaml -f compose.loadtest.yaml up --build`(backend: 1 vCPU, 512MiB,
> `-Xmx384m`)를 쓰고, k6는 로컬 바이너리 대신 `grafana/k6` 도커 이미지로
> 실행(`--add-host=host.docker.internal:host-gateway`,
> `BASE_URL=http://host.docker.internal:8080`)한다.

```
k6/
├── common/                 # 모든 시나리오가 공유
│   ├── accounts-seed.sql    # 테스트 계정(관리자/판매자/구매자) — 한 번만 실행
│   └── lib/                 # 로그인·CSRF·UUID 헬퍼
├── stock-race/              # 시나리오마다 자기 전용 DROP/옵션(seed.sql)과 검증(verify.sql)을 둔다
├── idempotency/
├── expiry/
├── drop-open-spike/
└── steady-read/
```

## 0. 사전 준비

1. k6 설치: https://k6.io/docs/get-started/installation/
2. 백엔드 기동: `docker compose up --build` (nano 제한 환경으로 돌리려면 GR-149의
   `docker compose -f compose.yaml -f compose.loadtest.yaml up --build`)
3. 테스트 계정 시드 (최초 1회, 재실행해도 안전):
   ```
   docker compose exec -T postgres psql -U grab -d grab -f - < k6/common/accounts-seed.sql
   ```
   테스트 계정: `k6-buyer@loadtest.local` / `LoadTest1234!` (자체 로그인, 카카오·구글·Toss 미사용).

## 1. 시나리오

실행 명령과 사전 준비는 각 시나리오 스크립트(`.js`) 상단 주석에 있다. seed.sql/
verify.sql 실행 명령은 각 SQL 파일 상단 주석을 본다.

| 시나리오 | 폴더 | 무엇을 보는지 | 결과 |
|---|---|---|---|
| 재고 경쟁 | [`stock-race/`](stock-race/stock-race.js) | 재고 5개에 VU 20명 동시 주문 → 초과판매 여부 | [result.md](stock-race/result.md) |
| 멱등성 | [`idempotency/`](idempotency/idempotency.js) | 동일 Idempotency-Key 동시 요청 → 중복 주문 여부 | [result.md](idempotency/result.md) |
| 결제 대기 만료 + 취소 동시성 | [`expiry/`](expiry/01-create-order.js) | 만료 배치와 취소 요청이 같은 주문을 두고 경합할 때 재고가 한 번만 반환되는지 (k6가 DB에 직접 접근할 수 없어 01→02(SQL로 payment_expires_at 수동 조정)→03 3단계로 나눠 실행) | [result.md](expiry/result.md) |
| 드롭 오픈 순간 스파이크 | [`drop-open-spike/`](drop-open-spike/spike.js) | 오픈 순간 조회 램프업 + 주문 스파이크에 서버가 버티는지 (seed.sql 실행 직후 바로 돌려야 함) | [result.md](drop-open-spike/result.md) |
| 평상시 조회 | [`steady-read/`](steady-read/steady-read.js) | 캐시 없는 상태의 목록/상세 조회 하한선 | [result.md](steady-read/result.md) |
| DB 커넥션 풀 고갈 | [`connection-pool-exhaustion/`](connection-pool-exhaustion/pool-exhaustion.js) | 풀 크기(기본 10)보다 뚜렷이 많은 동시 요청에 타임아웃/5xx가 나는지 | [result.md](connection-pool-exhaustion/result.md) |
