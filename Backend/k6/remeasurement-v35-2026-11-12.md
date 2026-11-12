# GRAB 성능 재측정 결과 (V35)

측정일: 2026-11-12  
브랜치: `sang9831` (`4974cb83`, 측정 중 소스 비교를 위한 임시 교체 후 원복)  
목적: V35 스키마와 제한 환경에서 동일 데이터로 기존 구현과 현재 구현을 비교한다.

## 결과 요약

| 항목 | Before | After | 판단 |
|---|---:|---:|---|
| 판매자 주문 목록 p95, 페이지 우선 조회 전후 | 2,080ms | 49.44ms | 약 97.6% 감소 |
| 판매자 주문 상세 p95, 목록 부하 동시 | 651.60ms | 39.77ms | 약 93.9% 감소. DB 풀 경합 완화 효과 포함 |
| 대시보드 DROP 통계 p95 | 10,770ms | 280.92ms | 약 97.4% 감소 |
| 대시보드 요약 p95 | 7,500ms | 362.20ms | 약 95.2% 감소 |
| `dropId` 지정 주문 목록 p95, seller JOIN → 직접 seller_id | 36.10ms | 46.82ms | 개선 확인 안 됨 |
| 배송 단계 갱신 SQL p95, 기존 인덱스 → V35 통합 인덱스 | 28.352ms | 33.614ms | p95 개선 확인 안 됨 |
| 배송 완료 대상 조회 SQL p95 | 21.546ms | 17.568ms | 약 18.5% 감소 |

주문 목록과 대시보드의 주요 개선은 확인됐다. 다만 `dropId` 지정 경로와 배송 단계 갱신 p95는 좋아지지 않았다. 인덱스 교체는 읽기 이득과 쓰기 비용을 함께 고려해야 한다.

## 코드·스키마 확인

- V30은 `orders.seller_id`를 backfill하고 `(seller_id, created_at, id)` 인덱스를 추가한다.
- V31~V33은 주문 상태/만료 조회 인덱스를 만들고 중복 인덱스를 정리한 뒤 결제 만료 partial 인덱스를 둔다.
- V34는 배송 주소와 취소 데이터를 `order_shipping_addresses`, `order_cancellations`로 분리하고 기존 `orders` 컬럼을 제거한다.
- V35는 이전 결제 만료 인덱스를 `(status, payment_expires_at, id) WHERE status IN ('PAYMENT_PENDING','SHIPPED')`로 교체한다. 인덱스를 하나 더 추가한 변경이 아니다.
- 주문 테이블의 인덱스는 측정 시 7개였다(기본키 포함). 배송지·취소 정보는 분리 테이블의 조회 경로를 사용한다.
- 현재 주문 목록은 `paymentStatus`가 없는 경우 페이지를 먼저 읽고 별도 단순 count를 실행한다. 상태 필터가 있는 경우 기존 LATERAL 기반 `findSellerOrders`를 호출한다. 상세는 주문/배송지/취소 정보를 조합해 응답한다.
- 대시보드 현재 경로는 요약용 집계 쿼리를 한 번 호출하고 DROP 통계는 페이지 우선 조회를 한다. 이전 경로는 요약을 여러 차례 조회하고 DROP별 LATERAL 집계를 전체 후보에 실행했다.
- 배송 배치에는 HTTP 트리거가 없어 scheduler SQL 두 경로를 직접 실행해 측정했다.

## 테스트 조건

- Compose load-test 설정으로 backend 2 vCPU/1 GiB, PostgreSQL 1 vCPU/512 MiB를 제한했다.
- PostgreSQL `max_connections=60`, Hikari 최대 풀 10을 확인했다.
- `netem.sh 2.8` 적용을 확인했다. 테스트 데이터는 전용 seller 계정 아래 DROP 1,000개, 주문 20,000개, 배송 주소 20,000개, 결제 20,000개, 배송 레코드 18,000개, 찜 20,000개로 구성했다.
- 주문 상태 분포: DELIVERED 16,000, SHIPPED 2,000, PAID 1,000, PAYMENT_PENDING 1,000. 배송 기록도 완료 16,000/진행 2,000으로 맞췄고, 배치가 측정 중 fixture를 변경하지 않도록 단계 갱신 시점을 설정했다.
- 모든 비교는 Flyway V35가 적용된 동일 스키마에서 수행했다. k6 시나리오별 15~30초 워밍업 후 60초씩 3회 측정했다. 주문은 목록 10 VU + 상세 10 VU, 대시보드는 요약 10 VU + DROP 통계 10 VU였다. 배송 SQL은 warmup 5회 후 반복 샘플 40개씩, 3회 측정했다.
- k6 HTTP 시나리오 오류율은 모든 보고 회차에서 0%였다. 메트릭은 3회 중앙값을 보고한다. 서로 다른 V34 이전 측정값은 비교에 사용하지 않았다.

## 판매자 주문 목록과 상세

### 페이지 우선 조회 전후

Before는 `ede52c84^`의 페이지 조회 + LATERAL count 경로, After는 현재 HEAD의 페이지 우선 + 단순 count 경로다. 두 버전은 같은 V35 fixture를 새로 시드해 실행했다.

| HTTP 지표 | 목록 Before | 목록 After | 상세 Before | 상세 After |
|---|---:|---:|---:|---:|
| 평균 | 1,720ms | 37.21ms | 335.79ms | 35.62ms |
| p50 | 1,700ms | 34.90ms | 311.63ms | 34.19ms |
| p90 | 2,000ms | 45.28ms | 547.41ms | 37.23ms |
| p95 | 2,080ms | 49.44ms | 651.60ms | 39.77ms |
| p99 | 2,260ms | 78.24ms | 1,110ms | 60.50ms |

3회 목록 p95는 Before `[2.08, 2.04, 2.13]초`, After `[49.44, 52.36, 45.60]ms`였다. 상세 p95는 각각 `[651.60, 638.46, 697.18]ms`, `[40.74, 39.77, 36.64]ms`다. 전체 처리량은 약 18.7~19.1 RPS에서 약 51 RPS로 증가했다. 에러는 없었다.

DB `pg_stat_statements`에서 Before 목록 본문 쿼리 평균은 1,012.827ms(873회), count는 660.845ms(878회)였다. After 페이지 쿼리는 평균 8.673ms(4,641회), 최대 84.042ms, count는 평균 7.088ms, 최대 79.022ms였다. Before에는 Hikari active 10, idle 0, pending 최대 6이 관측됐고, After에는 대부분 pending 0이었다. 상세 p95 개선에는 목록 병목으로 포화되던 커넥션 풀 경합 완화도 포함된다.

### seller_id 직접 필터의 격리 비교

페이지 우선 흐름은 고정하고 판매자 필터만 `orders JOIN drops`에서 `orders.seller_id`로 바꿔 비교했다.

| 경로 | JOIN Before p95 | 직접 seller_id After p95 | 판단 |
|---|---:|---:|---|
| `dropId` 없음 | 175.33ms | 46.08ms | 약 73.7% 감소 |
| `dropId` 지정 | 36.10ms | 46.82ms | 개선 불명확, 수치상 악화 |

`dropId` 없는 경로에서 페이지 본문 실행계획은 JOIN + 정렬 28.287ms/1,761 buffers에서 `idx_orders_seller_created` 역방향 index-only scan 0.323ms/7 buffers로 바뀌었다. 반면 단순 seller count는 데이터 대부분이 해당 seller에 속해 sequential scan을 선택했다. `dropId` 경로는 `existsDrop`, 소유권 확인, 목록 및 count 등 추가 호출이 남고 HTTP p95 이득이 재현되지 않았다. 따라서 직접 seller 필터의 HTTP 개선은 `dropId` 미지정 경로에 한정해 서술해야 한다.

주문 상세 구현은 JOIN 전후 격리 비교에서 동일했으며, 해당 수치는 인과 비교가 아니다. 상세 전후 수치는 페이지 우선 리팩터링으로 전체 DB 경합이 바뀐 비교에만 해당한다.

## 대시보드

Before는 `ede52c84^` 구현, After는 현재 구현이다. 같은 fixture 및 제한 환경에서 30초 워밍업 후 60초 3회 측정했다.

| API | 평균 Before → After | p95 Before → After | 변화 |
|---|---:|---:|---:|
| DROP 통계 | 약 8,370ms → 164.27ms | 10,770ms → 280.92ms | p95 약 97.4% 감소 |
| 요약 | 3,000ms → 233.32ms | 7,500ms → 362.20ms | p95 약 95.2% 감소 |

Before DB에서는 DROP LATERAL 본문 평균 7,893.30ms, 최대 11,596.94ms였다. 현재 페이지 쿼리는 평균 104.102ms, 최대 403.534ms이고 count는 평균 0.425ms였다. 요약은 기존 5개 쿼리 호출에서 `findSummaryMetrics` 단일 호출로 바뀌었으며 현재 DB 평균 183.671ms, 최대 910.420ms였다. Before 측정의 Hikari pending은 7~10까지 올라갔고 After는 짧은 순간 최대 4, 이후 대부분 0이었다. HTTP 에러는 양쪽 모두 없었다.

## COUNT LATERAL 조건부 실행

기존 변경은 NULL `paymentStatus`면 LATERAL을 생략하도록 `ON` 조건을 추가한 것이다. 실제 실행계획에서 준비 쿼리 플랜 종류가 결과를 바꿨다.

| 계획 모드, NULL 상태 | 기존 무조건 LATERAL | 조건부 LATERAL |
|---|---:|---:|
| custom plan | 1,663.643ms, 약 61,696 buffers, 20,000 loops | 10.768ms, 1,696 buffers, LATERAL 미실행 |
| generic plan | 62.630ms, 약 60,000 buffer hits, 20,000 loops | 58.124ms, 약 60,000 buffer hits, 20,000 loops |

Custom plan에서는 효과가 크지만 generic plan에서는 ON 조건이 있어도 내부 작업이 실행됐다. 그리고 현재 일반 목록(`paymentStatus=NULL`)은 이 LATERAL countQuery를 호출하지 않고 페이지 우선 + 단순 count로 우회한다. 상태 필터 요청은 값이 NULL이 아니어서 조건이 참이 되고 LATERAL을 실행한다. 따라서 이 최적화는 현재 일반 목록 경로의 개선 원인으로 주장하면 안 되며, generic plan을 쓰는 환경에서 생략 효과가 보장된다고도 말할 수 없다.

## 배송 배치 인덱스

기존 partial 인덱스와 V35 통합 인덱스를 같은 DB/fixture에서 각각 재구성하고 SQL 실행을 비교했다. 각 쿼리는 5회 워밍업 후 40회 샘플, 총 3회다.

| SQL 경로 | 이전 인덱스 p95 중앙값 | V35 인덱스 p95 중앙값 | 결과 |
|---|---:|---:|---|
| 배송 단계 갱신 UPDATE | 28.352ms | 33.614ms | 약 18.6% 느림 |
| 배송 완료 대상 SELECT | 21.546ms | 17.568ms | 약 18.5% 빠름 |

인덱스 저장 크기는 같은 fixture에서 재구성해 비교했다. V35 인덱스 96KB, 이전 partial 인덱스 32KB로 64KB 증가(약 3배)했다. 주문 인덱스 수는 7개로 유지된다. WAL 중앙값은 500건 INSERT에서 426,080 → 481,336 bytes(+13.0%), 1,000건 PAID→SHIPPED 상태 변경에서 909,328 → 1,043,392 bytes(+14.7%)였다.

결론적으로 완료 대상 읽기는 좋아졌지만, 갱신 p95는 나빠졌고 인덱스 크기/WAL 비용도 늘었다. 이 통합 인덱스는 단순한 무조건 성능 향상으로 표현할 수 없다. 실제 배치 실행 시간/빈도와 읽기·쓰기 비중을 함께 보고 채택 여부를 판단해야 한다.

## 측정 산출물과 재현

- 테스트 데이터 생성/정리: `scenarios/seller-order-read/seed-v35-remeasurement.sql`, `cleanup-v35-remeasurement.sql`
- 주문 계획: `seller-query-v35-explain.sql`, `count-query-v35-explain.sql`
- 대시보드/주문 k6 파라미터: 각 시나리오의 JS 파일에서 seller, drop, page 수를 환경 변수로 조정한다.
- 배송 인덱스 크기 및 WAL 측정: `scenarios/shipment-progress-soak/benchmark-index-consolidation.sql`
- 회차별 k6 JSON 및 리소스 샘플은 작업 PC 임시 디렉터리 `$env:TEMP\grab-v35-remeasurement`에 보관되어 있다. 이 임시 산출물은 저장소에 포함하지 않았다.

측정 중 테스트 계정으로 만든 fixture는 정리했다. 저장소에는 측정용 harness/SQL과 본 보고서만 남겼으며, 커밋·푸시는 하지 않았다.
