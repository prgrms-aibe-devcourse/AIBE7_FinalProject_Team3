# 판매자 주문 목록/상세 조회 (seller-order-read, GR-174)

> 공통 실행 환경·전제는 `../README.md` 참고. steady-read(GR-153)와 같은 성격 — 정확한
> 캐파시티가 아니라 캐시 없이 조회가 평상시 수준 동시 조회를 버티는 하한선을 본다.
> 목록(`findSellerOrders`)·상세(`OrderDetailReader`) 모두 판매자 전체를 조회·집계하므로
> (드롭 이름으로 거르지 않음), GR-173과 같은 이유로 공용 `k6-seller` 대신 이 시나리오
> 전용 판매자(`k6-order-seller`)를 쓴다.

## 2026-10-27 측정: micro, 30초, 기준선 vs order_burst 겹침 비교

`compose.loadtest-micro.yaml`(vCPU 2개, 1GiB, `-Xmx550m`)로 기동, `seed.sql` 실행
(전용 판매자 `k6-order-seller`에 DROP 1개·결제 완료 주문 1000건, 재고는 50만 개로
넉넉히 둬 재고 경쟁이 섞이지 않게 함) 후 `list_read`(VU 10)·`detail_read`(VU 10)를
30초간 동시 실행. 작업 내용 3(오픈 직후 주문 폭주와 겹치는 시점)을 보려고
`order_burst`(10~20초 구간, 20건/초 = 총 200건, 실제 `/api/v1/orders` 호출) 유무로
두 번 측정해 비교했다.

### 기준선 — `ENABLE_BURST=false`(순수 steady-state)

- 체크 1635/1635(100%) 성공, `http_req_failed` 0.00%
- `list_read`: avg 19.56ms, med 13.38ms, p90 22.26ms, **p95 37.63ms**
- `detail_read`: avg 15.37ms, med 7.79ms, p90 15.15ms, **p95 30.94ms**

### order_burst 겹침 — 기본값(20건/초 × 10초 = 200건)

- 체크 1854/1854(100%) 성공(`order_burst`의 `주문 201` 포함), `http_req_failed` 0.00%
- `list_read`: avg 19.97ms, med 13.06ms, p90 25.7ms, **p95 49.64ms**(기준선 대비 +12ms)
- `detail_read`: avg 12.3ms, med 6.91ms, p90 13.33ms, **p95 30.8ms**(기준선과 거의 동일)
- 메모리 527.8MiB/1GiB(51.54%), OOM·에러 로그 없음
- `verify.sql`: 주문 1200건(시드 1000 + 버스트 200, 기대대로), 재고 카운터 범위 이탈
  0건, 품목 없는 고아 주문 0건

`list_read`는 주문이 실시간으로 늘어나는 동안 p95가 어느 정도 올라갔다(37.63ms →
49.64ms) — `findSellerOrders`가 매 호출마다 전체 주문을 `created_at DESC`로 다시
스캔·정렬하면서 쓰기 트랜잭션과 경합한 것으로 보인다. 반면 `detail_read`는 단건
조회라 영향이 거의 없었다. 두 경우 모두 steady-read 수준(한 자릿수~50ms대)을
유지했고, 이 규모(주문 1000~1200건, 버스트 20건/초)에서는 뚜렷한 병목은 아니다.

## 결론

- **완료조건 1(시나리오 존재)**: 충족.
- **완료조건 2(응답시간 하한선 기록)**: 충족 — `list_read` p95 37.63ms(기준선)/
  49.64ms(버스트 겹침), `detail_read` p95 30.94ms(기준선)/30.8ms(버스트 겹침).
- **작업 내용 3(오픈 스파이크와 겹치는 시점)**: 확인함 — `list_read`가 버스트 중
  p95 약 32% 증가, `detail_read`는 영향 없음. 둘 다 에러나 뚜렷한 저하는 아니었다.

## 측정 환경의 한계

- VU 20(list 10 + detail 10)·30초는 steady-read와 같은 축소판 하한선이며, 운영
  트래픽 규모를 반영한 수치가 아니다.
- `order_burst`는 실제 drop-open-spike(GR-152)처럼 WISH→GRAB 전환 타이밍이나 다수
  동시 구매자를 흉내 내지 않고, 이미 열린 DROP에 단일 버퍼(20건/초)로 주문만
  꾸준히 추가하는 단순화된 프록시다 — 실제 "판매 시작 순간"의 트래픽 패턴과는
  다를 수 있다.
- 주문 1000~1200건은 steady-read 수준 축소판이라, 판매자당 주문이 수만 건
  이상으로 늘어나면 `findSellerOrders`의 전체 스캔·정렬 비용이 더 커질 수 있다
  (검증 안 함).

## 2026-10-27 측정: GR-191 인덱스(idx_orders_drop_created) 적용 전/후, 15만건

위 "검증 안 함" 한계를 확인하려고 `k6-order-seller` 드롭에 일회성으로 주문 15만건을
시드(이 벤치마크 전용 SQL, 측정 후 `seed.sql`로 1000건 상태 복구)하고,
`idx_orders_drop_created`를 지웠다 만들었다 하며 전/후를 비교했다(로컬 docker compose,
micro 제한 없음).

**DB 쿼리 단(EXPLAIN ANALYZE, `findSellerOrders` 본문 쿼리, 1페이지)**:
- 적용 전: `Parallel Seq Scan` + 외부 정렬(디스크 병합, ~17MB) — **78.1ms**
- 적용 후: `Index Scan Backward using idx_orders_drop_created` — **0.2ms**(약 390배)

**k6 HTTP 레이어(`ENABLE_BURST=false`, list VU10·detail VU10, 20초)**:
- 적용 전: `list_read` p95 **1.08s**(avg 820ms)
- 적용 후: `list_read` p95 **1.10s**(avg 873ms) — 사실상 변화 없음

본문 쿼리는 390배 빨라졌는데 API 전체 p95는 그대로였다. 원인은 같은 리포지토리 메서드의
`countQuery`(페이지네이션 총 개수)가 `paymentStatus` 필터가 없어도 무조건
`LEFT JOIN LATERAL`로 결제 최신 상태를 매 행(15만건)마다 조회하기 때문 — `EXPLAIN
ANALYZE`로 **726.8ms**(버퍼 450,594회 히트)가 나왔고, 이 count 쿼리는 drop_id로만
필터링해 정렬이 필요 없으므로 `idx_orders_drop_created`의 영향권 밖이다.

**countQuery 수정**: `LEFT JOIN LATERAL ... ON TRUE`를 `ON CAST(:paymentStatus AS
VARCHAR) IS NOT NULL`로 바꿔, `paymentStatus`가 없을 때 LATERAL 서브쿼리 자체를
건너뛰게 했다(`OrderRepository.findSellerOrders`). `PREPARE`/`EXECUTE`로 바인드
파라미터 플랜을 직접 확인함:
- `paymentStatus=NULL`: `One-Time Filter: false`로 LATERAL이 완전히 스킵됨 —
  726.8ms → **52.9ms**(약 14배)
- `paymentStatus='SUCCEEDED'`: 기존과 동일하게 LATERAL 실행, 결과 정확성 유지(변화 없음,
  애초에 이 경우는 필터링에 필요한 비용이라 최적화 대상이 아님)
- 기존 회귀 테스트(`OrderRepositoryTest#findsSellerOrdersWithLatestPaymentAndOptionalFilters`)가
  필터 있음/없음 두 경로 모두 통과함을 확인(5개 테스트 전부 통과)

**운영 DB 왕복(RTT) 반영**: 위 수치는 로컬 docker 네트워크(RTT 사실상 0)에서 잰
DB 실행시간만이다. 운영 Supabase psql 실측 RTT(`SELECT 1` 기준 3.2~3.5ms, 3ms로
계산)를 더하면, `findSellerOrders` 목록 조회 1페이지는 본문 쿼리·countQuery 각각
별도 DB 호출이라 왕복 2회가 든다:
- 적용 전: 78.1ms(본문) + 726.8ms(count) + 2×3ms(RTT) = **약 810.9ms**
- 적용 후(인덱스+countQuery 수정 모두 반영): 0.2ms(본문) + 52.9ms(count) + 2×3ms(RTT)
  = **약 59.1ms**
- API 기준 개선폭은 **약 13.7배**다. "본문 쿼리만 390배"는 countQuery 병목을 가리는
  과장된 비교였고, 실제 체감 개선은 이 13.7배가 맞다.
