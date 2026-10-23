# k6 부하테스트 (GR-150)

로컬 백엔드(`localhost:8080`)만 대상으로 한다. 운영/외부 서비스로는 쏘지 않는다.

## 0. 사전 준비

1. k6 설치: https://k6.io/docs/get-started/installation/
2. 백엔드 기동: `docker compose up --build` (nano 제한 환경으로 돌리려면 GR-149의
   `docker compose -f compose.yaml -f compose.loadtest.yaml up --build`)
3. 테스트 데이터 시드 (재실행 가능, 매 시나리오 전 재고 초기화 용도로도 다시 실행한다):
   ```
   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/seed.sql
   ```
   출력된 `drop_id`, `option_id`를 아래 `-e` 옵션에 그대로 쓴다. 테스트 계정은
   `k6-buyer@loadtest.local` / `LoadTest1234!` (자체 로그인, 카카오·구글·Toss 미사용).

## 1. 재고 경쟁 (stock-race.js)

```
k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/01-stock-race/stock-race.js
```
재고 5개(seed.sql 기준)에 VU 20명이 동시 주문 → 성공 5건, 나머지 `INSUFFICIENT_STOCK`(422).

## 2. 멱등성 (idempotency.js)

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/seed.sql   # 재고 초기화
k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/02-idempotency/idempotency.js
```

## 3. 결제 대기 만료 + 취소 동시성 (3단계로 나눠 실행)

k6는 DB에 직접 접근할 수 없어 `payment_expires_at`(10분 고정)을 당기는 단계만 SQL로 수동 처리한다.

```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/seed.sql   # 재고 초기화

k6 run -e DROP_ID=1 -e OPTION_ID=1 Backend/k6/03-expiry/01-create-order.js
# 콘솔에 출력된 ORDER_ID를 아래에 복사

docker compose exec -T postgres psql -U grab -d grab -v order_id="'<ORDER_ID>'" -f - < Backend/k6/03-expiry/02-backdate.sql

k6 run -e ORDER_ID=<ORDER_ID> Backend/k6/03-expiry/03-cancel-order.js
```
취소 요청과 배치(10초 주기)가 같은 주문을 두고 경합한다. 어느 쪽이 먼저 처리하든
재고는 한 번만 반환돼야 한다.

## 검증

각 시나리오 실행 후:
```
docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/common/verify.sql
```
