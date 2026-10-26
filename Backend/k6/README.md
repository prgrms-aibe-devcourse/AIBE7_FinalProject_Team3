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
└── login-burst/             # 로그인 동시 폭주(검증 SQL 대신 관찰 명령과 세션 정리 스크립트를 둔다)
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

## 5. 로그인 동시 폭주 (login-burst)

로그인 비밀번호 검증(Argon2id, 해시 1회에 약 19MiB)이 동시에 몰릴 때 nano 환경이 어느 VU
단계부터 무너지는지 본다(GR-161). 아래 명령은 모두 `Backend/`에서 Git Bash로 실행한다.

**준비**

```
docker compose -f compose.yaml -f compose.loadtest.yaml up -d --build
docker compose logs backend | head -3     # NOTE: Picked up JDK_JAVA_OPTIONS: -Xmx384m -Xlog:gc 확인
docker compose logs backend | grep '\[gc\] Using'   # nano 제한에서는 Serial
docker compose exec -T postgres psql -U grab -d grab -f - < k6/common/accounts-seed.sql

# 측정 전 Redis 상태 기록(측정 후와 비교해 로그인 폭주가 남긴 증가량을 본다)
docker compose exec -T redis redis-cli DBSIZE
docker compose exec -T redis redis-cli INFO memory | grep '^used_memory_human'
```

**실행**

VU 단계(5 → 10 → 20 → 40, 40에서도 버티면 80)마다 따로 실행한다. 합격 기준(thresholds)은
없으며, 5xx·타임아웃·힙 `OutOfMemoryError`·컨테이너 종료가 처음 나온 단계를 한계 단계로 기록한다.

```
k6 run -e VUS=5 k6/login-burst/login-burst.js                 # steady: 30초 동안 계속 로그인
k6 run -e VUS=5 -e SHAPE=burst k6/login-burst/login-burst.js  # burst: VU마다 1번씩 동시에
k6 run -e VUS=20 -e MODE=fail k6/login-burst/login-burst.js   # 없는 이메일로 실패 로그인(비교용)
```

- `MODE=fail`은 success의 한계 단계와 바로 아래 단계에서만 실행한다. 없는 계정도 더미 해시로
  Argon2를 실행하므로 비용이 같고, 세션을 만들지 않는다.
- 결과는 k6 요약의 `login_*_total`, `csrf_failed_total`, `login_duration`(로그인 요청만의 응답시간)으로 읽는다.
  `DURATION`으로 steady 유지 시간을 바꿀 수 있다(기본 30s).

**관찰** (k6 실행과 함께 다른 터미널에서)

```
# CPU·메모리(약 1~2초 간격). Ctrl+C로 멈춘다
while true; do echo "$(date +%T) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' backend-backend-1)"; done | tee stats.log

# 실행 후: 컨테이너가 죽었는지(재시작 정책이 없어 OOM-kill이면 exited로 남는다)
docker inspect backend-backend-1 --format 'Status={{.State.Status}} OOMKilled={{.State.OOMKilled}} RestartCount={{.RestartCount}}'

# 실행 후: 힙 부족과 GC. 컨테이너 OOM-kill과 구분해 기록한다
docker compose logs --since 5m backend | grep -E 'OutOfMemoryError|ERROR'
docker compose logs --since 5m backend | grep '\[gc\]' > gc.log
```

GC 로그 한 줄은 `Pause Young (Allocation Failure) 210M->48M(370M) 4.123ms` 형태다(GC 전 → 후 힙 사용량,
괄호는 현재 확보된 힙, 끝은 정지 시간). `Pause Full`이 잦아지거나 GC 뒤에도 사용량이 거의 줄지 않으면
힙이 한계에 온 것이다. Grafana(`localhost:3000`)는 15초 수집이라 짧은 피크를 놓칠 수 있어 보조로만 본다.

**정리**

success 모드는 로그인마다 Redis에 세션 키 2개(TTL 14일)를 남긴다. 모든 실행이 끝나면 측정 후 상태를
기록하고, k6 계정(`k6-buyer@loadtest.local`)의 세션만 지운다. 다른 회원의 세션은 남는다.

```
docker compose exec -T redis redis-cli DBSIZE
docker compose exec -T redis redis-cli INFO memory | grep '^used_memory_human'

USER_ID=$(docker compose exec -T postgres psql -U grab -d grab -tAc "SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local'")
docker compose exec -T redis sh -c 'redis-cli EVAL "$(cat)" 0 "$1"' _ "$USER_ID" < k6/login-burst/cleanup-sessions.lua   # 지운 세션 수

docker compose exec -T redis redis-cli DBSIZE   # 측정 전 값으로 돌아왔는지 확인
```

측정이 끝나면 `docker compose up -d`로 제한 없는 개발 환경으로 되돌린다.
