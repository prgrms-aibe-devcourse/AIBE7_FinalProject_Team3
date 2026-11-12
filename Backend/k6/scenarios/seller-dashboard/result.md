# 판매자 대시보드 통계 조회 (seller-dashboard, GR-173)

> 공통 실행 환경·전제는 `../../README.md` 참고. steady-read(GR-153)와 같은 성격 — 정확한
> 캐파시티가 아니라 캐시 없이 집계 쿼리가 평상시 수준 동시 조회를 버티는 하한선을 본다.
> 대시보드 요약·DROP 통계 API는 판매자 전체를 집계하므로(드롭 이름으로 거르지 않음),
> 공용 `k6-seller` 대신 이 시나리오 전용 판매자(`k6-dashboard-seller`)를 쓴다 — 다른
> soak 시나리오가 같은 판매자 밑에 남긴 드롭·주문이 섞이면 "DROP 200개" 전제와 집계
> 수치가 둘 다 오염되기 때문이다.

## 2026-10-27 측정(재검증): micro, 30초, 시나리오별 p95 분리

`k6/compose/compose.loadtest-micro.yaml`(vCPU 2개, 1GiB, `-Xmx550m`)로 기동, `seed.sql` 실행
(전용 판매자 `k6-dashboard-seller`에 DROP 200개·DROP당 결제 완료 주문 5건·위시 20명 =
주문 1000건, 위시 4000건) 후 `summary_read`(VU 10)·`drop_stats_read`(VU 10)를 30초간
동시 실행. k6 `thresholds`에 시나리오 태그(`{scenario:...}`)를 걸어 두 요청 유형의
지연시간을 분리해 기록했다(둘을 합산한 p95는 쿼리 복잡도가 다른 두 엔드포인트를
섞어 어느 쪽도 정확히 대표하지 못하므로 쓰지 않는다).

- 체크 1621/1621(100%) 성공, `http_req_failed` 0.00%
- **`drop_stats_read`**(`findDropStats`, DROP당 LATERAL 조인 2개): avg 19.67ms,
  med 11.1ms, p90 18.05ms, **p95 24.39ms**
- **`summary_read`**(`countDropsByStatus`·`countOrdersByStatus`·`countPaymentsByStatus`·
  `countReconciliationRequired`·`sumStock`, 집계 5종): avg 21.11ms, med 12.15ms,
  p90 19.65ms, **p95 28.18ms**
- 전체 합산(참고용, 결론 근거로는 쓰지 않음): p95 27.22ms, max 684.13ms(초반 워밍업
  아웃라이어로 추정)
- 메모리 632.6MiB/1GiB(61.78%), OOM·에러 로그 없음
- `verify.sql`: 주문 1000건·위시 4000건(seed.sql 기대값과 정확히 일치), 재고 카운터
  변동 0건 — 읽기 전용 시나리오가 실제로 쓰기를 하지 않았음을 확인

이번 규모(DROP 200개·주문 1000건)에서는 `findDropStats`의 LATERAL 조인 2개가
`summary_read`의 단순 집계 5종보다 오히려 p95가 **더 낮게** 나왔다 — DROP당 조인은
무겁지만 페이지당 20건으로 제한(LIMIT/OFFSET)되는 반면, `summary_read`는 판매자의
주문·결제 전체(1000건+)를 매번 풀스캔성으로 집계하기 때문으로 보인다. 두 쿼리 다
steady-read 수준(한 자릿수~20ms대)을 유지했고, 이 규모에서는 둘 다 뚜렷한 병목이
아니다.

## 2026-11-12 측정: orders 정규화(V34) 및 drops 조인 제거 최적화 (micro + db-nano)

### 1. 변경 배경 및 최적화 내용
- `orders` 테이블 정규화(V34)로 배송지(`order_shipping_addresses`) 및 취소(`order_cancellations`)를 분리하고 `paid_at` 컬럼을 제거.
- `SellerDashboardRepository`의 집계 쿼리(`countOrdersByStatus`, `countPaymentsByStatus`, `countReconciliationRequired`)가 불필요하게 `drops` 테이블을 조인하던 것을 V30 비정규화 컬럼인 `orders.seller_id`를 직접 조회하도록 최적화.

### 2. DB 쿼리 최적화 실측 (EXPLAIN ANALYZE, micro + db-nano)
| 쿼리 대상 | 기존 쿼리 (개선 전) | 개선 후 쿼리 | 실행 시간 개선 | 플래닝 및 I/O 절감 |
|---|---|---|---|---|
| **주문 상태별 건수** (`countOrdersByStatus`) | `JOIN drops d ON d.id = o.drop_id AND d.seller_id = 3` | `WHERE o.seller_id = 3` | **7.58 ms ➔ 3.66 ms (2.1배 단축)** | `drops` 풀스캔 및 Hash Join 1회 제거 |
| **결제 상태별 건수** (`countPaymentsByStatus`) | `payments p JOIN orders o JOIN drops d ...` | `payments p JOIN orders o WHERE o.seller_id = 3` | **8.40 ms ➔ 1.15 ms (7.3배 대폭 단축)** | 3-way 조인 ➔ 2-way 조인 축소, 해시 테이블 1개 제거 |
| **미해결 보정 건수** (`countReconciliationRequired`) | `JOIN drops d ...` | `WHERE o.seller_id = 3` | **0.071 ms ➔ 0.069 ms** | 플래닝 시간 1.48ms ➔ 0.95ms (-35%), 버퍼 629 ➔ 491 블록 (-22%) |

> 대시보드 요약 1회 호출 시 실행되는 핵심 집계 쿼리 합산 시간이 **16.05ms ➔ 4.88ms로 약 70% 감소**하여 DB CPU 점유율과 커넥션 점유 시간이 크게 절감되었습니다.

## 결론

- **완료조건 1(시나리오 존재)**: 충족.
- **완료조건 2(응답시간 하한선 기록)**: 충족 — `findDropStats` p95 24.39ms,
  대시보드 요약 p95 28.18ms(시나리오별로 분리 기록).

## 측정 환경의 한계

- VU 20(summary 10 + drop_stats 10)·30초는 steady-read와 같은 축소판 하한선이며,
  운영 트래픽 규모를 반영한 수치가 아니다.
- seed.sql의 DROP 200개는 "평상시보다 훨씬 많은" 수준을 임의로 정한 값이라, 실제
  상위 판매자의 DROP·주문 규모와 다를 수 있다. DROP·주문 규모가 더 커지면
  `summary_read`의 풀스캔성 집계가 `findDropStats`보다 먼저 느려질 가능성이 있다
  (이번 측정에서 이미 그 경향이 보인다).
- max 684.13ms 아웃라이어 1건의 원인(JIT/커넥션 워밍업 추정)은 별도로 확인하지 않았다.

## 2026-11-12 재측정: micro + DB nano + 운영 RTT

`compose.loadtest-micro.yaml`(backend 2 vCPU·1GiB) + `compose.loadtest-db-nano.yaml`
(PostgreSQL 1 vCPU·512MiB·max_connections=60) + `netem.sh 2.8`(운영 RTT 모사),
기본 HikariCP 풀 10, 30초, `summary_read` 10 VU + `drop_stats_read` 10 VU로 측정했다.
시드 데이터는 DROP 200개·주문 1,000건·WISH 4,000건이며, 모든 요청이 성공했다.

| API | 평균 | p95 |
|---|---:|---:|
| `/seller/dashboard/summary` | 54.92ms | **131.44ms** |
| `/seller/dashboard/drops` | 72.70ms | **180.20ms** |

서버 내부 JDBC 호출 시간 로그(p95)는 summary의 5개 직렬 집계 합계 86.07ms였다.
DROP 통계는 본문 집계 117.55ms, 전체(본문 + count) 127.83ms였고, count만은 11.01ms였다.
따라서 DROP 통계의 지연은 전체 건수 쿼리가 아니라 페이지 본문 집계(LATERAL의 재고·위시·
주문/품목 계산)에 집중됐다. Summary도 5개의 개별 DB 왕복이 누적되어 p95가 커졌다.
같은 측정 뒤 Hikari pending 0, timeout 0이어서 풀 증설은 개선 근거가 없다.

이번 HTTP p95는 변경 전과 같은 조건으로 실행한 비교값이 아니다. 2026-10-27 기준선
(24.39ms·28.18ms)은 무제한 DB 왕복 환경 결과라 이번 운영 RTT 모사 수치와 직접 비교하지 않는다.
Windows Docker Desktop의 로컬 포트포워딩 및 1회 측정 한계도 있다.

### 최적화 전 커밋과 같은 조건으로 비교

최적화 전 `e4a19df2`와 적용 후 `ede52c84`를 같은 V34 DB·시드(200 DROP·주문 1,000건·
WISH 4,000건)에서 비교했다. backend micro + DB nano + RTT 2.8ms, 30초, 각 시나리오
10 VU이며, 비교 회차는 각 코드에서 2회씩 측정했다.

| API p95 | 최적화 전 1·2회 | 최적화 후 1·2회 | 각 중앙값 | 변화 |
|---|---:|---:|---:|---:|
| 요약 | 39.25 / 39.24ms | 40.06 / 32.41ms | 39.25 → 36.24ms | -7.7% |
| DROP 통계 | 43.07 / 43.65ms | 42.72 / 42.21ms | 43.36 → 42.47ms | -2.1% |

네 회차 모두 요청 오류 0%였다. 현행 첫 측정에서 나온 131.44/180.20ms는 반복 회차와
크게 달라 안정화 비교에서 제외했다. 원인은 확정하지 못했다. 재현된 결과로는 SQL 단일
실행시간 개선(주문/결제/보정 집계 16.05ms → 4.88ms)이 이 데이터 규모의 API p95를
유의미하게 낮췄다고 보기 어렵다. DROP 통계는 본문 집계가 여전히 가장 큰 쿼리 구간이다.
### 2026-11-12 변경 전후 (요약 단일 쿼리·페이지 우선 통계)

같은 backend micro + DB nano, RTT 2.8ms, 기존 시드(200 DROP/1,000 주문), 30초·각 10 VU 조건이다.
변경 전은 코드 수정 전 실행, 변경 후는 구현 이미지 재기동 후 실행했다. 각 조건 1회 측정이며
통계 유의성보다 같은 조건에서의 방향 확인용이다. 변경 전/후 모두 오류 0%였다.

| API p95 | 변경 전 | 변경 후 | 차이 |
|---|---:|---:|---:|
| 요약 | 41.77ms | 24.54ms | -41.2% |
| DROP 통계 | 47.31ms | 28.50ms | -39.8% |

초기 변경 후 실행은 백엔드 기동 직후 CSRF 요청이 EOF로 실패해 제외했다. health가 UP인 것을 확인하고
재실행한 결과를 사용했다. 변경 전·후 결과는 각각 1회이므로 반복 측정으로 분산을 확인할 필요가 있다.
