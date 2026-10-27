# 공개 DROP 목록 쿼리 대용량 기준선 (drop-list-scale, GR-164)

> 공통 실행 환경·전제는 `../README.md` 참고. 이 시나리오는 다른 시나리오와 달리 **nano 제한 환경이 아니다**(아래 측정 환경).

GR-153(steady-read)은 DROP 15건 seed라 공개 목록 쿼리의 실제 비용이 드러나지 않았다. DROP 수천 건 규모에서
정렬(날짜 / 인기 WISH / 인기 GRAB) × 필터(없음 / categoryId / keyword / soldOut) 12개 조합의 실행 계획과
응답시간을 기록하고, 측정 전 병목 후보마다 개선할지 판단한다. 이 단계에서는 애플리케이션 코드와 Flyway를 바꾸지 않았다.

## 측정 환경

| 항목 | 값 |
| --- | --- |
| 머신 | Apple M5(10코어), Docker Desktop VM 8GB / 10 CPU |
| 컨테이너 | `compose.yaml` + `compose.swapoff.yaml` — backend·postgres `mem_limit` 2GiB = `memswap_limit`(스왑 0), CPU 제한 없음 |
| 자원 기록 | `stats.sh` 2초 간격. 스왑 72샘플 모두 0, 최대 메모리 backend 555MiB·postgres 197MiB(2GiB 한도에 닿지 않음) |
| 부하 경로 | k6 컨테이너를 compose 네트워크에 붙여 `http://backend:8080`으로 직접 호출 |

nano(512MiB) 제한이 아니므로 다른 시나리오의 수치와 직접 비교하지 않는다. 같은 조건에서의 **조합 간 상대 비교와 개선 전후 비교**가 목적이다.

## seed (`seed.sql`, 기본값)

| 항목 | 규모·분포 |
| --- | --- |
| DROP | 5,000 — WISH 1,750 · GRAB 1,750(품절 221) · ENDED 1,000(품절 404) · DRAFT 250 · CANCELED 250 |
| 옵션 | 12,500(DROP당 1~4개, 비활성 417) |
| 이미지 | GALLERY 1~3장 + DETAIL 1장 |
| WISH | 64,316(활성 51,504). DROP당 `60 × random()^3`로 소수 상품에 쏠림 |
| 주문 | 26,481(결제 확정·미취소 18,499, 결제 후 취소 10%, 미결제 만료 20%) |
| 키워드·카테고리 | 이름 끝 단어 19종(n % 19), 카테고리 8종((n / 20) % 8) — 상태(n % 20)와 겹치지 않는 주기 |

`setseed`로 분포를 고정하고 마지막에 `VACUUM (ANALYZE)`를 실행한다. WISH·GRAB은 판매 시작·종료를 3일 이후로 두고 미결제 주문은 `EXPIRED`로 넣어, 측정 중 상태 전환·결제 만료 배치가 데이터를 바꾸지 않게 했다.

## 측정 절차

```
docker compose -f compose.yaml -f k6/drop-list-scale/compose.swapoff.yaml up -d
docker compose exec -T postgres psql -U grab -d grab -f - < k6/common/accounts-seed.sql
docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -f - < k6/drop-list-scale/seed.sql
k6/drop-list-scale/stats.sh "$PWD/k6/drop-list-scale/out/<이름>/stats.txt" &   # 측정 후 종료
k6/drop-list-scale/explain.sh <이름> 5
docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 -e BASE_URL=http://backend:8080 \
  grafana/k6 run --summary-export drop-list-scale/out/<이름>/k6-summary.json drop-list-scale/drop-list.js
```

- `explain.sh`: auto_explain(ANALYZE·BUFFERS)을 잠시 켜고 조합마다 워밍업 1회 후 5회 호출해, 앱이 실제로 보낸 SQL·바인딩 값 그대로의 본 쿼리·count 쿼리 계획을 모은다. 끝나면 설정을 되돌린다.
- `drop-list.js`: 조합마다 VU 10 × 20초를 순서대로 실행(조합 간 간섭 없음). page=0, size=20.
- keyword는 `스니커즈`(약 5% 선택도), categoryId는 1(FASHION), soldOut은 `false`(후보가 많은 쪽).
- 원자료는 `out/<이름>/`에 남는다(gitignore).

## 기준선 결과 (2026-10-27)

### k6 응답시간 (ms) — 293,729건, 실패 0

| 정렬 | 필터 | 중앙값 | p95 | p99 |
| --- | --- | ---: | ---: | ---: |
| 날짜 | 없음 | 4.8 | 9.0 | 13.9 |
| 날짜 | categoryId | 2.5 | 4.5 | 6.0 |
| 날짜 | keyword | 8.7 | 12.2 | 15.0 |
| 날짜 | soldOut | 43.3 | **49.2** | 52.8 |
| 인기 WISH | 없음 | 10.6 | 13.6 | 16.1 |
| 인기 WISH | categoryId | 4.0 | 6.8 | 8.6 |
| 인기 WISH | keyword | 6.5 | 9.7 | 12.0 |
| 인기 WISH | soldOut | 25.8 | **30.8** | 36.9 |
| 인기 GRAB | 없음 | 56.4 | **64.3** | 68.5 |
| 인기 GRAB | categoryId | 9.2 | 12.2 | 14.7 |
| 인기 GRAB | keyword | 8.4 | 11.3 | 13.4 |
| 인기 GRAB | soldOut | 64.3 | **74.0** | 86.7 |

### 실행 계획 (auto_explain 5회 중앙값, 버퍼는 shared hit)

| 정렬 | 필터 | 본 쿼리 ms | 본 쿼리 버퍼 | count ms | count 버퍼 |
| --- | --- | ---: | ---: | ---: | ---: |
| 날짜 | 없음 | 1.69 | 512 | 1.03 | 210 |
| 날짜 | categoryId | 0.64 | 519 | 0.10 | 13 |
| 날짜 | keyword | 3.16 | 528 | 1.97 | 225 |
| 날짜 | soldOut | 5.15 | **14,099** | 4.30 | **13,812** |
| 인기 WISH | 없음 | 3.46 | 5,730 | 0.32 | 217 |
| 인기 WISH | categoryId | 0.71 | 1,134 | 0.17 | 218 |
| 인기 WISH | keyword | 1.05 | 759 | 0.65 | 232 |
| 인기 WISH | soldOut | 7.38 | **14,509** | 2.42 | **8,997** |
| 인기 GRAB | 없음 | 17.54 | **60,442** | 1.96 | 5,509 |
| 인기 GRAB | categoryId | 4.01 | 7,953 | 0.39 | 733 |
| 인기 GRAB | keyword | 3.00 | 3,509 | 1.19 | 507 |
| 인기 GRAB | soldOut | 18.24 | **61,992** | 3.21 | **13,167** |

버퍼 수와 서브쿼리 반복 횟수(loops)는 재측정해도 같았지만 소요 시간은 회차마다 흔들렸다(`grab_none` 본 쿼리 28.4ms → 17.5ms, 같은 버퍼 60,442). 개선 전후 비교는 **버퍼·loops를 1차 지표**, 시간은 보조 지표로 본다.

## 병목 분석

### 1. 인기 GRAB 정렬 — 판매 수량 집계 (`grab_none` 계획)

- 정렬 키 `((SubPlan 6)) DESC`가 판매 수량 합(`orders` ⋈ `order_items`)이라, LIMIT 20 전에 판매 기간·재고 조건을 통과한 후보 **1,531건마다** 서브쿼리가 실행된다.
- `orders`는 `idx_orders_drop_status (drop_id, status, id)`로 `drop_id`만 찾고 `paid_at IS NOT NULL AND canceled_at IS NULL`은 힙에서 거른다(후보당 약 6.8행).
- `order_items`를 `uq_order_items_order_option`으로 **10,367회** 조회한다.
- 본 쿼리 버퍼 60,442 중 **48,524(80%)**가 이 정렬 키 계산이다.

### 2. soldOut 필터 — 가용 재고 합계 반복 계산 (`date_soldout` 계획)

- 필터 `(SELECT COALESCE(SUM(total - reserved - sold - withheld), 0) = 0 …) = :soldOut`가 후보 **4,503건마다** 실행된다(필터 없는 날짜 정렬은 후보를 읽기만 하고 서브쿼리는 LIMIT 이후 20행에만 실행된다).
- 본 쿼리 버퍼 512 → 14,099(약 28배), count 쿼리 210 → 13,812(약 66배). count 쿼리도 같은 필터를 다시 계산한다.
- 서브쿼리 1회 비용은 작지만(약 0.001ms) 후보 전체에 곱해져, API p95가 날짜 정렬 9.0ms → 49.2ms, 인기 WISH 13.6ms → 30.8ms로 오른다.

### 3. 병목이 아닌 후보

- **keyword**(`ILIKE '%스니커즈%'`, 약 5% 선택도): 본 쿼리 0.5~3.2ms, p95 9.7~12.2ms. 이 규모에서 순차 스캔 비용이 작다.
- **활성 WISH 집계**: `wish_none`은 기존 `idx_wishes_drop_active`로 Index Only Scan(후보 1,750건, 버퍼 5,730), p95 13.6ms.

## 후보별 판단

| 후보 | 판단 | 근거 | 후속 |
| --- | --- | --- | --- |
| 결제 확정 주문 집계(인기 GRAB) | **개선** | p95 64.3 / 74.0ms, 버퍼 80%가 정렬 키 | GR-176 |
| 가용 재고 합계 중복 계산 | **개선** | soldOut 조합 버퍼 28~66배, p95 30.8~49.2ms | GR-177 |
| `pg_trgm` keyword 인덱스 | 보류 | keyword 조합 p95 12.2ms 이하 | 데이터가 커져 병목이 되면 재검토 |
| 활성 WISH 부분 인덱스 | 보류 | 기존 인덱스로 Index Only Scan | — |
| Redis 조회 캐시 | 보류 | 쿼리 개선 후에도 병목이 남을 때 판단(TECHSTACK.md) | — |

## 측정 중 바로잡은 것

1. **k6 접속 경로**: 처음엔 `host.docker.internal`(Docker Desktop 포트 포워딩)로 보냈는데 `dial: i/o timeout` 42건이 나왔다. 백엔드 로그는 정상이어서 측정 경로 문제로 보고 compose 네트워크 직접 호출로 바꿨다(이후 실패 0).
2. **스왑**: 처음엔 기본 `compose.yaml`로 돌려 스왑이 허용된 상태였다(측정 후 스왑 사용량은 0). 조건을 명확히 하려고 `compose.swapoff.yaml`로 스왑을 막고 자원 사용량을 기록하며 다시 측정했다. 위 결과가 이 측정이다.
3. **visibility map**: seed 직후 `ANALYZE`만 하면 autovacuum 시점에 따라 `wish_none`이 Index Only Scan ↔ Bitmap Heap Scan으로 바뀌었다. seed 마지막을 `VACUUM (ANALYZE)`로 바꿔 매번 같은 상태에서 측정한다.
4. **seed 분포**: 키워드 단어와 상태를 같은 주기(n % 20)로 정해 단어 하나가 한 상태에만 걸렸다. 단어 n % 19, 카테고리 (n / 20) % 8로 바꿨다.

## 한계

- 로컬 단일 머신(Apple M5)이고 운영·nano 사양이 아니다. 절대값은 운영 응답시간이 아니라 조합 간·전후 비교용이다.
- auto_explain의 ANALYZE 계측 오버헤드가 시간에 포함된다. k6 측정 중에는 꺼져 있다.
- 첫 페이지(page=0, size=20)만 측정했다. 깊은 페이지의 OFFSET 비용은 보지 않았다.
- 동시 사용자 10명 고정이다. 처리량 한계나 커넥션 풀 포화는 범위 밖이다.
- seed 규모(DROP 5,000)를 넘는 데이터에서의 증가 추세(선형 여부)는 측정하지 않았다.
