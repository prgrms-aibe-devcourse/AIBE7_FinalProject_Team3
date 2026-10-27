# k6 부하테스트 (GR-150)

로컬 백엔드(`localhost:8080`)만 대상으로 한다. 운영/외부 서비스로는 쏘지 않는다.

> 각 시나리오 결과는 **운영 캐파시티 수치가 아니라 하한선 검증**이다. 목표는
> 운영(t3.small, vCPU 2개/2GB)보다 메모리가 약한 t3.nano(vCPU 2개/0.5GB, 베이스라인
> CPU 성능 vCPU당 5%) 환경에서도 재고 동시성이 깨지지 않는다는 것을 확인하는 것이고,
> 정확한 RPS·응답시간을 측정하는 것이 아니다. t3 계열은 nano~large가 전부 vCPU
> 2개이고 메모리·베이스라인 CPU%만 다르다(CPU 코어 수를 1개로 제한했던 건 잘못된
> 전제였다 — 2026-10-26 수정). nano 제한 환경으로 돌릴 때는 `docker compose -f
> compose.yaml -f compose.loadtest-nano.yaml up --build`(backend: vCPU 2개, 512MiB,
> `-Xmx384m`, 스왑은 memswap_limit으로 차단)를 쓰고, k6는 로컬 바이너리 대신
> `grafana/k6` 도커 이미지로 실행(`--add-host=host.docker.internal:host-gateway`,
> `BASE_URL=http://host.docker.internal:8080`)한다. t3.micro(vCPU 2개, 1GiB) 수준으로
> 비교하고 싶으면 `compose.loadtest-nano.yaml` 대신 `compose.loadtest-micro.yaml`을
> 합친다 — 둘은 같은 `backend` 서비스를 겹쳐 쓰므로 동시에 쓰지 않고 하나만 고른다.

```
k6/
├── common/                 # 모든 시나리오가 공유
│   ├── accounts-seed.sql    # 테스트 계정(관리자/판매자/구매자) — 한 번만 실행
│   └── lib/                 # 로그인·CSRF·UUID 헬퍼
├── stock-race/              # 시나리오마다 자기 전용 DROP/옵션(seed.sql)과 검증(verify.sql)을 둔다
├── idempotency/
├── expiry/
├── drop-open-spike/
├── steady-read/
├── login-burst/
└── sse-stream/              # 연결 유지는 Node(sse-clients.mjs), 주문은 k6(order-load.js)
```

## 0. 사전 준비

1. k6 설치: https://k6.io/docs/get-started/installation/
2. 백엔드 기동: `docker compose up --build` (nano 제한 환경으로 돌리려면 GR-149의
   `docker compose -f compose.yaml -f compose.loadtest-nano.yaml up --build`,
   t3.micro 수준은 `compose.loadtest-micro.yaml`로 바꿔 쓴다. SSE 시나리오는 그 뒤에
   `-f compose.loadtest-sse.yaml`을 덧붙인다)
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
| 로그인 동시 폭주 | [`login-burst/`](login-burst/login-burst.js) | 로그인(Argon2) 요청이 동시에 몰릴 때 어느 VU부터 5xx·힙 OOM이 나는지, 실패 로그인도 같은 비용인지 (`compose.loadtest-micro.yaml`로 측정. 초기 측정은 임시 설정, 스크립트 상단 주석 참고) | [result.md](login-burst/result.md) |
| SSE 동시 연결 | [`sse-stream/`](sse-stream/order-load.js) | SSE 200 연결 유지 + 주문 50/초에서 연결 실패·알림 유실·재고 정합성·주문 p95 악화 여부 (`compose.loadtest-micro.yaml` + `compose.loadtest-sse.yaml`로 측정, 한 회차는 [`run.sh`](sse-stream/run.sh)로 실행) | [result.md](sse-stream/result.md) |
| 결제 배치 롱런(soak) | [`payment-batch-soak/`](payment-batch-soak/payment-batch-soak.js) | 평상시 수준 주문·결제·취소 트래픽을 장시간 유지해 결제 배치의 메모리·커넥션 누수 여부를 확인 (`GRAB_PAYMENT_MOCK_GATEWAY_ENABLED=true`로 실행해 실제 토스를 안 때림) | [result.md](payment-batch-soak/result.md) |
| PG 장애 주입 | [`payment-fault-injection/`](payment-fault-injection/payment-fault-injection.js) | MockPaymentGateway로 결제 승인 거절·결과불명·취소 결과불명을 섞어, 결제 배치(진행 중 결제 정리·취소 재요청)가 해소하는지와 보정 환불 중복 생성 여부를 확인 (`GRAB_PAYMENT_MOCK_GATEWAY_ENABLED=true`와 `GRAB_MOCK_*_RATE`로 실행) | [result.md](payment-fault-injection/result.md) |
| 공개 DROP 목록 대용량 기준선 | [`drop-list-scale/`](drop-list-scale/drop-list.js) | DROP 5,000건 seed에서 정렬(날짜/인기 WISH/인기 GRAB) × 필터(없음/categoryId/keyword/soldOut) 12개 조합의 실행 계획(`explain.sh`, auto_explain)과 p95를 기록해 개선 후보를 판단하고, 동시 접속 VU 25→800 단계별 처리량·p95·HikariCP 대기(`concurrency.sh`)를 개선 전 기준선으로 남김 (nano 제한 대신 `compose.swapoff.yaml`로 스왑만 차단, k6는 compose 네트워크에서 실행) | [result.md](drop-list-scale/result.md) |
| WISH 판매 알림 메일 폭주 | [`wish-mail-storm/`](wish-mail-storm/seed.sql) | DROP 오픈 시 WISH 수천 명 규모 알림 메일이 `noticeMailTaskExecutor`(pool=1) 큐에서 적체·유실 없이 처리되는지, 동시/연속 전이가 서로 지연시키는지 (k6 대신 seed.sql + Mailpit 수신 시각 비교로 측정) | [result.md](wish-mail-storm/result.md) |
| 드롭 상태 전이 배치 | [`drop-transition-batch/`](drop-transition-batch/seed.sql) | DROP 수백 건이 같은 시각에 동시 전이 대상이 될 때 `DropTransitionService`(BATCH_SIZE=500)가 한 스케줄러 주기 안에 전부 처리하는지, 전이 이벤트(메일 트리거)의 동기 수신자 조회가 배치 루프를 지연시키는지 (k6 대신 seed.sql + DB 상태 폴링으로 측정) | [result.md](drop-transition-batch/result.md) |
| 배송 진행 배치 롱런(soak) | [`shipment-progress-soak/`](shipment-progress-soak/seed.sql) | 평상시 수준의 배송 중 주문량을 장시간 유지해 `ShipmentProgressScheduler`(배송 단계 Mock 자동 진행)의 메모리·커넥션 누수, 배송 상태 전이 중복·유실 여부를 확인 (`GRAB_MOCK_DELIVERY_ENABLED=true`로 실행, HTTP 트리거가 없는 배치라 k6 대신 seed.sql + feed-shipments.sh로 부하를 건다) | [result.md](shipment-progress-soak/result.md) |
| 판매자 대시보드 통계 조회 | [`seller-dashboard/`](seller-dashboard/seller-dashboard.js) | 판매자 1명이 DROP 200개·DROP당 주문 5건·위시 20명을 가진 상태에서 대시보드 요약·DROP별 통계 집계 쿼리(LATERAL 조인 2개)가 평상시 수준 동시 조회를 버티는 응답시간 하한선 | [result.md](seller-dashboard/result.md) |
