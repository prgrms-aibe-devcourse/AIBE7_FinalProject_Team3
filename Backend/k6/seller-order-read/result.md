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
