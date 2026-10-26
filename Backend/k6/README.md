# k6 부하테스트 (GR-150)

로컬 백엔드(`localhost:8080`)만 대상으로 한다. 운영/외부 서비스로는 쏘지 않는다.

```
Backend/k6/
├── common/                 # 모든 시나리오가 공유
│   ├── accounts-seed.sql    # 테스트 계정(관리자/판매자/구매자) — 한 번만 실행
│   └── lib/                 # 로그인·CSRF·UUID 헬퍼
├── 01-stock-race/           # 시나리오마다 자기 전용 DROP/옵션(seed.sql)과 검증(verify.sql)을 둔다
├── 02-idempotency/
├── 03-expiry/
├── 04-drop-open-spike/
└── 05-steady-read/
```

## 0. 사전 준비

1. k6 설치: https://k6.io/docs/get-started/installation/
2. 백엔드 기동: `docker compose up --build` (nano 제한 환경으로 돌리려면 GR-149의
   `docker compose -f compose.yaml -f compose.loadtest.yaml up --build`)
3. 테스트 계정 시드 (최초 1회, 재실행해도 안전):
   ```
   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/accounts-seed.sql
   ```
   테스트 계정: `k6-buyer@loadtest.local` / `LoadTest1234!` (자체 로그인, 카카오·구글·Toss 미사용).

## 1. 재고 경쟁 (01-stock-race)

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/01-stock-race/seed.sql
k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/01-stock-race/stock-race.js
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/01-stock-race/verify.sql
```
재고 5개에 VU 20명이 동시 주문 → 성공 5건, 나머지 `INSUFFICIENT_STOCK`(422).

## 2. 멱등성 (02-idempotency)

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/02-idempotency/seed.sql
k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/02-idempotency/idempotency.js
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/02-idempotency/verify.sql
```

## 3. 결제 대기 만료 + 취소 동시성 (03-expiry, 3단계로 나눠 실행)

k6는 DB에 직접 접근할 수 없어 `payment_expires_at`(10분 고정)을 당기는 단계만 SQL로 수동 처리한다.

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/03-expiry/seed.sql

k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/03-expiry/01-create-order.js
# 콘솔에 출력된 ORDER_ID를 아래에 복사

docker compose exec -T postgres psql -U grab -d grab -v order_id="'<ORDER_ID>'" -f - < Backend/k6/03-expiry/02-backdate.sql

k6 run -e ORDER_ID=<ORDER_ID> Backend/k6/03-expiry/03-cancel-order.js

docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/03-expiry/verify.sql
```
취소 요청과 배치(10초 주기)가 같은 주문을 두고 경합한다. 어느 쪽이 먼저 처리하든
재고는 한 번만 반환돼야 한다.

## 4. 드롭 오픈 순간 스파이크 (04-drop-open-spike)

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/04-drop-open-spike/seed.sql
k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/04-drop-open-spike/spike.js
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/04-drop-open-spike/verify.sql
```
seed.sql 실행 직후 바로 k6를 돌려야 한다(DROP 상태가 WISH, `sale_starts_at`이 30초 뒤로
잡혀 있음). 목록/상세 조회가 램프업하다가 오픈 시각에 일부 VU가 주문을 시도한다.
레이트리밋이 없으므로 유입 제한 없이 그대로 받았을 때 서버가 버티는지(응답 불가·
재시작 여부는 `docker stats`/`docker compose logs backend`로 확인), 재고 정합성이
깨지지 않는지를 본다.

## 5. 평상시 조회 (05-steady-read)

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/05-steady-read/seed.sql
k6 run Backend/k6/05-steady-read/steady-read.js
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/05-steady-read/verify.sql
```
DROP_ID/OPTION_ID를 넘길 필요 없다 — k6가 `keyword`로 seed.sql이 만든 더미 DROP
풀을 직접 조회한다. 인기 드롭 1개를 반복 상세조회(`hot_read`)하는 트래픽과, 매번
다른 드롭을 조회(`random_read`)하는 트래픽을 동시에 steady-state로 유지한다.
조회 캐시(Redis)는 아직 없어서(TECHSTACK.md 87절) 이 결과는 캐시 없이 DB가
평상시 조회를 얼마나 버티는지의 하한선이며, DB 커넥션(HikariCP) 사용량은
GR-159 Grafana 대시보드로 함께 본다.

## 6. SSE 동시 연결 (06-sse-stream)

k6 기본 이미지는 SSE 스트림을 읽지 못해 연결 유지는 Node(`sse-clients.mjs`, 의존성 없음, Node 18+)가,
재고 변경 원천인 주문 트래픽은 k6(`order-load.js`)가 맡는다. 반드시 `compose.loadtest.yaml`을 합쳐 띄운다.
SSE의 IP당 연결 제한을 풀고, postgres에 `pg_stat_statements`를 적재한다(seed.sql이 이것에 의존한다).

```
docker compose -f compose.yaml -f compose.loadtest.yaml up --build -d
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/06-sse-stream/seed.sql

# 터미널 1: SSE 연결 유지 (기본 100개·300초)
CONNECTIONS=200 DURATION_SEC=300 OUT=Backend/k6/06-sse-stream/out/sse.json \
  node Backend/k6/06-sse-stream/sse-clients.mjs

# 터미널 2: ready가 다 뜬 뒤(약 RAMP_SEC=20초) 주문 투입. DURATION은 SSE 유지 시간보다 짧게 둔다
RATE=50 DURATION=4m k6 run --console-output=Backend/k6/06-sse-stream/out/orders.log \
  Backend/k6/06-sse-stream/order-load.js

# 전달 지연 계산, 재고 정합성·쿼리 호출 수 확인
node Backend/k6/06-sse-stream/analyze.mjs Backend/k6/06-sse-stream/out/sse.json Backend/k6/06-sse-stream/out/orders.log
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/06-sse-stream/verify.sql
```

- **SSE 비활성 기준선**: 터미널 2만 같은 RATE·DURATION으로 돌린 주문 p95(`http_req_duration{name:order}`).
  SSE 활성 시 악화가 10% 이내인지 비교한다(REALTIME_STOCK.md 13.2).
- k6를 도커 이미지로 돌릴 때는 `-v "$PWD/Backend/k6:/k6"`로 마운트하고 경로를 `/k6/...`로,
  `BASE_URL=http://host.docker.internal:8080`으로 바꾼다. 지연 계산은 두 프로세스의 시계가 같다는 전제라
  도커 VM 시계 오차가 섞일 수 있다(결과에 기록).
- 재실행 전에는 seed.sql을 다시 돌린다(주문 정리 + `pg_stat_statements` 초기화).
- 연결 제한 동작(같은 IP 21번째 연결 429, 201번째 연결 503) 확인은 오버라이드를 끄고 따로 한다:
  `GRAB_STOCKSTREAM_MAXCONNECTIONSPERIP=20 GRAB_STOCKSTREAM_CONNECTRATEMAX=30 docker compose ... up -d` 후
  `CONNECTIONS=25 node ...sse-clients.mjs`의 `statuses`에서 429 5건을 확인한다.
  201번째 연결 503은 기본 오버라이드 상태(IP 상한 1000 > 전체 상한 200)에서 `CONNECTIONS=205`로 실행해
  503 5건을 확인한다. 서버는 IP 제한을 먼저 검사하므로 IP 상한이 200 이하면 429가 먼저 난다.
