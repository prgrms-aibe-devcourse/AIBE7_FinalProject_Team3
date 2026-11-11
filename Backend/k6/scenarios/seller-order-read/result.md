# 판매자 주문 목록/상세 조회 (seller-order-read, GR-174)

> 공통 실행 환경·전제는 `../../README.md` 참고. steady-read(GR-153)와 같은 성격 — 정확한
> 캐파시티가 아니라 캐시 없이 조회가 평상시 수준 동시 조회를 버티는 하한선을 본다.
> 목록(`findSellerOrders`)·상세(`OrderDetailReader`) 모두 판매자 전체를 조회·집계하므로
> (드롭 이름으로 거르지 않음), GR-173과 같은 이유로 공용 `k6-seller` 대신 이 시나리오
> 전용 판매자(`k6-order-seller`)를 쓴다.

## 2026-10-27 측정: micro, 30초, 기준선 vs order_burst 겹침 비교

`k6/compose/compose.loadtest-micro.yaml`(vCPU 2개, 1GiB, `-Xmx550m`)로 기동, `seed.sql` 실행
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

**이 59.1ms 추정은 틀렸다 — `dropId`를 리터럴로 넘긴 EXPLAIN만 쟀고, k6로 실제 HTTP
p95를 재측정한 적이 없었다.** 아래 GR-214 절 참고.

## 2026-11-06 측정: GR-214 — `dropId` 미지정(판매자 전체 조회) 경로는 전혀 다른 쿼리였다

GR-191/GR-194가 "13.7배 개선"이라고 결론낸 측정은 전부 `WHERE drop_id = <리터럴>`
조건의 EXPLAIN이었다. 그런데 `list_read`가 실제로 호출하는
`/api/v1/seller/orders?page=&size=`(이 시나리오 코드 자체가 처음부터 `dropId`를
넘기지 않는다)는 `orders`에 `seller_id`가 없어 `JOIN drops d ON d.id=o.drop_id
AND d.seller_id=:sellerId`를 거쳐야 했고, 그 순간 `idx_orders_drop_created`의
"Index Scan Backward + LIMIT" 효과가 사라져 판매자의 주문 전체(15만건)를 스캔하며
LATERAL을 15만 번 실행했다(EXPLAIN 기준 414.7ms, 본문 쿼리만).

k6로 실측(수정 전, 15만건, `ENABLE_BURST=false`, list VU10 + detail VU10, 60s):
**`list_read` p95 1.1~1.3s** — GR-191이 추정한 59.1ms와 무관했고, 인덱스 적용 전
추정치(810.9ms)보다도 나빴다.

**수정(GR-214)**: `orders`에 `seller_id` 컬럼을 비정규화로 추가(V30 마이그레이션,
`drops.seller_id`로 백필, FK 없음) + `idx_orders_seller_created(seller_id,
created_at, id)` 인덱스. `findSellerOrders`를 `JOIN drops` 대신
`WHERE o.seller_id=:sellerId`로 변경해 `dropId` 유무와 무관하게 항상 이 인덱스를
타게 했다.

**DB 쿼리 단(EXPLAIN ANALYZE, 15만건, seller_id=3)**:
- 본문 쿼리: `Index Only Scan Backward using idx_orders_seller_created` + LIMIT 20,
  **0.159ms**(LATERAL도 20행에만 실행)
- countQuery: 여전히 `seller_id` 전체(15만건)를 스캔해 COUNT(*)해야 해서
  **41.5~49.9ms**(캐시 warm/cold 큰 차이 없음) — 이건 페이지네이션 총 건수 집계의
  본질적 비용이라 GR-214 범위에서 더 줄이지 않았다.

**k6 HTTP 레이어(수정 후, 15만건, 조건 동일)**:
- `list_read`: avg 456.38ms, med 379.32ms, p90 787.9ms, **p95 832.27ms**
- `detail_read`: avg 8.61ms, p95 15.12ms(영향 없음)
- 수정 전 대비 **약 35~40% 개선**(1.1~1.3s → 832ms)이지만, DB 쿼리 단 합계(0.16ms +
  50ms ≈ 50ms)보다 훨씬 크다 — 커넥션 획득·트랜잭션 경계·직렬화 등 애플리케이션
  오버헤드가 섞여 있는데, VU1 순차 호출(동시성 없음)에서도 바닥값이 ~208ms로
  일정해 동시성 경합은 아니다. 같은 코드로 1000건 규모를 재보면 이 바닥값이
  사라진다(`list_read` p95 17.65ms, VU20) — 그래서 150,000건 규모의 판매자에게만
  나타나는, `seller_id` COUNT(*) 전체 스캔 비용과 연동된 현상으로 보였다.

### 832ms 중 서버는 76ms만 쓴다 — 나머지는 로컬 측정 환경의 아티팩트였다

위 832ms가 서버/DB 문제인지 측정 환경 문제인지 가리려고, `LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG`·
`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG`·`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG`로
띄운 디버그용 backend 컨테이너에 같은 VU10(list)+VU10(detail) 부하를 걸고, 애플리케이션 로그에서
`listOrders`로 매핑된 시각부터 응답을 쓰기 시작한 시각까지(= 보안 필터 통과 이후 핸들러
실행 전체, DB 쿼리 포함)를 직접 계산했다(204건 샘플):

- **p50 41ms, p90 58ms, p95 76ms, max 151ms**

이건 EXPLAIN으로 추정한 "본문 0.16ms + count 41.5~49.9ms"와 정확히 들어맞는다. 즉 **서버가
실제로 쓰는 시간은 10개 동시 요청 상태에서도 p95 76ms로 이미 빠르다.** k6가 측정한
p95 832ms와 핸들러 p95 76ms 사이의 약 750ms 차이는 애플리케이션·DB 코드가 아니라
**이 요청이 도달하기 전/떠난 후의 구간**(k6 ↔ 컨테이너 사이)에서 생긴다.

이 측정 환경은 Windows + Docker Desktop이고, `-p 8080:8080` 포트포워딩은 WSL2를 거치는
유저모드 프록시(vpnkit류)를 통한다. 이 프록시는 동시 연결 수가 적을 때는 문제없지만
(health check 10~15ms, 공개 DROP 목록 30~50ms, 순차 측정에서 모두 정상) 10~20개 동시
연결이 몰리면 지연·지터가 크게 튀는 것으로 알려진 한계가 있다. 서버 로그상 모든 요청이
균일하게 빠른데 k6가 재는 왕복시간만 튀는 패턴이 이 프록시 병목과 일치한다. 이 포트포워딩
계층은 로컬 Docker Desktop 전용이고 운영 EC2(Linux)에는 없으므로, 832ms는 운영 체감과
무관한 이 로컬 측정 도구만의 잡음이다.

### t3.micro(2 vCPU, 1GiB) 제약 환경에서 수정 전/후 재측정 — 진짜 운영 캐파시티 비교

위 측정은 전부 CPU 제한 없는 docker compose였다. 운영(t3.micro)과 비교하려면 `k6/compose/compose.loadtest-micro.yaml`로
backend를 vCPU 2개·1GiB로 제한하고 같은 조건(15만건, list VU10+detail VU10, 60s)으로 재야 한다 — **수정 전 코드도
같은 micro 제약에서 재측정**해 앞의 "제한 없음" 비교와 섞이지 않게 했다(수정 전 코드는 `git checkout main`으로
돌아가 재빌드, V30 마이그레이션 파일만 남겨 Flyway 히스토리를 유지했다).

**서버 핸들러 처리시간(DEBUG 로깅으로 직접 계산, 같은 VU10+10 부하)**:

| | 수정 전 | 수정 후 |
|---|---|---|
| p50 | 1,484ms | 31ms |
| p90 | 2,140ms | 45ms |
| **p95** | **2,338ms** | **57ms** |
| max | 3,058ms | 679ms |

vCPU 2개로는 15만 행 전체 스캔 + LATERAL 15만 번을 감당 못 해 핸들러 안에서만 p50 1.48초가 걸렸다(제한 없는
환경의 EXPLAIN 414.7ms보다 훨씬 나쁨 — CPU가 적을수록 이 쿼리가 더 크게 벌어진다는 뜻). 수정 후에는 57ms로
무관하게 빠르다. **핸들러 기준 개선폭은 약 41배(2,338ms → 57ms)다.**

**k6 HTTP 레이어(같은 micro 제약, 참고용)**:
- 수정 전: `list_read` p95 **1.84s**
- 수정 후: `list_read` p95 **1.2s**
- 역시 로컬 측정 잡음이 섞여 핸들러 기준 개선폭(41배)만큼 깨끗하게 안 나타난다(약 35% 개선으로만 보임).
  운영 체감 개선폭을 말할 때는 이 k6 숫자가 아니라 위 핸들러 p95(2,338ms→57ms)를 근거로 삼아야 한다.

### 15만건은 비현실적 — 2만건(현실적 상한선)으로 다시 재다

15만건은 "판매자 한 명이 여기까지 가도 안 깨지나"를 보려던 극단치였지, 현실적인 규모가
아니다. 플랫폼의 동시 사용자 참고 목표(800~1000명, GR-180, REALTIME_STOCK.md)에서
역산하면 — 드롭 오픈 한 번에 재고로 막혀 수백 건 단위로 주문이 나고, 아주 잘 되는
판매자가 드롭을 꾸준히(주 1~2회) 2년 돌려도 전체 누적은 **5,000~30,000건** 선이
현실적인 상한선이다. 그래서 15만 대신 **2만건**으로 다시 쟀다.

또한 이전 측정은 로컬 docker 네트워크(DB 왕복 사실상 0ms)였다. 운영은 DB가 외부
Supabase라 요청마다 왕복이 붙는데, 이 차이를 종이 계산("+6ms")으로 어림잡았던 건
부정확했다 — `k6/compose/compose.loadtest-db-nano.yaml`(postgres를 Supabase Nano
수준으로 제한) + `netem.sh`(postgres 송신 패킷에 실제 지연 주입)로 **진짜** 왕복
지연을 재현해야 한다. 이 PC에서는 `netem.sh 2.8`이 운영 실측(3.2~3.4ms)과 맞았다
(`db-nano/check.sh`로 `SELECT 1` p50 3.330ms 확인).

**최종 측정 조건**: `compose.loadtest-micro.yaml`(backend vCPU 2개/1GiB) +
`compose.loadtest-db-nano.yaml`(postgres vCPU 1개/512MiB, `max_connections=60`) +
`netem.sh 2.8`(운영 RTT 모사) + **HikariCP 풀은 기본값(10) 그대로** + 2만건, list
VU10+detail VU10, 30s. 수정 전 코드도 똑같은 조건에서 다시 쟀다(`git checkout main`
재빌드, V30 마이그레이션 파일만 남겨 Flyway 히스토리 유지).

**서버 핸들러 처리시간(DEBUG 로깅으로 직접 계산)**:

| | 수정 전 | 수정 후 |
|---|---|---|
| p50 | 819ms | 24ms |
| p90 | 1,104ms | 29ms |
| **p95** | **1,174ms** | **30ms** |
| max | 1,645ms | 714ms |

`detail_read`도 같이 쟀다 — 수정 전엔 이것도 같이 느렸다(p95 455ms). `list_read`가
커넥션을 오래 붙잡아 같은 HikariCP 풀을 쓰는 `detail_read`까지 커넥션을 못 받아
굶었기 때문이다(池 경합). 수정 후엔 `detail_read`도 p95 34ms로 돌아왔다 — GR-214는
목록 조회 하나만 고친 게 아니라 **커넥션 풀 전체를 숨 쉬게 만든 수정**이었다.

| | 수정 전 | 수정 후 |
|---|---|---|
| `detail_read` p95 | 455ms | 34ms |

**k6 HTTP 레이어**: `list_read` p95 1.26s(수정 전) → **58.87ms**(수정 후), `detail_read`
p95 371ms(수정 전) → **60.09ms**(수정 후). 이번엔 k6 숫자와 핸들러 숫자가 거의
일치한다 — 15만건·풀 무제한일 때 보였던 "k6만 유독 큰" 괴리가 2만건·현실적 조건에서는
사라졌다(그 괴리 자체가 15만건이라는 비현실적 규모가 만든 2차 효과였다는 뜻).

**풀 크기는 건드릴 필요가 없었다**: 처음엔 커넥션 풀을 30으로 올려서야 `detail_read`가
회복되는 걸 보고 "풀 경합이 남은 문제"라고 봤지만, 그건 15만건이라는 비현실적 규모 +
postgres vCPU 1개가 겹쳐 countQuery 자체가 CPU로 포화(`docker stats`로 postgres
98~99% 확인)됐을 때 얘기였다. 현실적인 2만건에서는 **기본 풀(10) 그대로도** 수정된
쿼리면 충분히 빠르다. 풀 크기 변경은 `connection-pool-exhaustion`(GR-156)이 이미
"범위 밖"으로 남겨둔 영역이고(단, 그 검증은 운영 RTT를 재현 안 해서 이번 발견과는
조건이 다르다), 블루/그린 전환 중 2×풀크기가 Supabase Nano `max_connections=60`에
닿을 수 있어(풀 30이면 2×30=60, 여유 0) 섣불리 올리면 안 된다 — 필요해지면 별도
검증으로 다룬다.

## 결론 (최종)

- **가장 신뢰할 수 있는, 운영에 가장 가까운 수치**: 2만건(현실적 규모) + 운영 RTT(netem
  2.8ms) + t3.micro + 기본 HikariCP 풀로 잰 서버 핸들러 p95 — `list_read` **1,174ms →
  30ms(약 39배)**, `detail_read` **455ms → 34ms(약 13배)**. k6 HTTP p95도 이 조건에서는
  같은 수준(`list_read` 1.26s→58.87ms, `detail_read` 371ms→60.09ms)으로 일치한다.
- **15만건·CPU 무제한·CPU 제한(micro)·RTT 없음 등 앞선 측정들은 전부 중간 과정이다** —
  각각 "dropId 리터럴만 잰 측정이 실제 경로와 다르다", "k6 HTTP p95가 로컬 측정
  환경(Windows Docker Desktop 포트포워딩) 잡음을 포함한다", "비현실적 규모(15만건)에서는
  DB CPU 포화라는 별개 문제가 섞인다" 같은, 측정을 더 정교하게 만든 발견들이었지만
  최종 수치로 쓰면 안 된다. 과정은 기록으로 남기되, **인용은 위 2만건/기본 풀 수치로
  한다.**
- GR-191/GR-194가 "완료"로 표시했던 "13.7배(59.1ms)"는 `dropId` 리터럴 조회만 해당하는
  수치였고, 실제 흔한 조회 경로(판매자 전체)는 GR-214 전까지 측정된 적도 고쳐진 적도
  없었다는 게 이번 재측정의 핵심 교훈이다. 앞으로 이 종류의 "인덱스 추가" PR은 조회
  조건(필터 유무)별로 실제 호출되는 쿼리 모양까지 맞춰 재측정해야 하고, 가능하면
  처음부터 운영 RTT(`db-nano`)와 현실적인 데이터 규모로 재서 측정 환경 잡음과 비현실적
  규모가 섞인 숫자를 보고하지 않는다.

## 2026-11-12 측정: orders 정규화(V34, 인덱스 정리) 및 LATERAL 정렬 단순화 (micro + db-nano)

### 1. 변경 배경 및 최적화 내용
- `orders` 테이블 정규화(V34):
  - 배송지 정보(`order_shipping_addresses`)와 취소 정보(`order_cancellations`)를 1:1 분리.
  - 미사용 중복 인덱스 `idx_orders_drop_created` 삭제 (`idx_orders_seller_created`로 일원화).
  - 난수 기반 주문번호 제약 `uq_orders_order_number` 삭제.
- `OrderRepository.findSellerOrders`:
  - `LEFT JOIN order_cancellations`로 취소 진행 여부 확인.
  - 최신 결제 상태 LATERAL 조인의 정렬 키를 `ORDER BY p.created_at DESC, p.id DESC`에서 단일 PK 정렬인 `ORDER BY p.id DESC`로 단순화하여 인메모리 퀵소트 오버헤드 절감.

### 2. DB 쿼리 최적화 실측 (EXPLAIN ANALYZE, micro + db-nano)

- **`findSellerOrders` 본문 쿼리**:
  - 최신 결제 상태 LATERAL 조인의 정렬 키를 `ORDER BY p.created_at DESC, p.id DESC`에서 단일 PK 정렬인 `ORDER BY p.id DESC`로 단순화.
  - 실행 시간: **0.74 ms ➔ 0.40 ms (약 45.9% 단축)**
  - 플래닝 및 정렬 방식: 다중 컬럼 정렬에서 PK 단일 컬럼 정렬로 단순화되어 퀵소트 오버헤드와 버퍼 히트 감소.
- **인덱스 정리 효과**:
  - 미사용 인덱스 `idx_orders_drop_created` 및 난수 주문번호 유니크 제약 `uq_orders_order_number` 삭제로 `orders` 테이블 인덱스가 9개에서 7개로 감축.
  - `idx_orders_seller_created`가 판매자/DROP 복합 조회를 온전히 인덱스 스캔으로 커버하면서, 주문 생성 등 쓰기 시 인덱스 갱신 오버헤드 절감.
