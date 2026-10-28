# 배송 진행 배치 롱런 soak (shipment-progress-soak, GR-172)

> 공통 실행 환경·전제는 `../README.md` 참고. GR-154(결제 배치 soak)와 같은 측정 방식
> (`docker stats` + `pg_stat_activity`/HikariCP 추이)을 쓰지만, `ShipmentProgressScheduler`가
> HTTP 트리거 없이 순수 DB 상태(시간 경과)만 보고 도는 배치라 k6 대신 SQL 시드 + 지속
> 유입 스크립트로 부하를 건다(GR-171 drop-transition-batch와 같은 이유).

## 사전 준비

```
GRAB_MOCK_DELIVERY_ENABLED=true docker compose -f compose.yaml -f compose.loadtest-micro.yaml up --build
docker compose exec -T postgres psql -U grab -d grab -f - < k6/common/accounts-seed.sql   # 최초 1회
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/shipment-progress-soak/seed.sql
```

## 실행

```
k6/shipment-progress-soak/monitor-resources.sh backend-backend-1 60 > soak-resources.log &
DURATION_SECONDS=7200 k6/shipment-progress-soak/feed-shipments.sh
```

종료 후:

```
docker compose exec -T postgres psql -U grab -d grab -f - < k6/shipment-progress-soak/verify.sql
```

## 2026-10-27 측정: micro, 15분 스모크

`GRAB_MOCK_DELIVERY_ENABLED=true`로 `compose.loadtest-micro.yaml`(vCPU 2개, 1GiB,
`-Xmx550m`) 기동 후 초기 40건(`seed.sql`) + `DURATION_SECONDS=900 INTERVAL_SECONDS=30
BATCH_SIZE=5`로 `feed-shipments.sh` 실행(30회 투입, 총 150건 추가 — soak 종료 시점
누적 190건).

**메모리**: 459.6MiB(44.88%) → 481.2MiB(46.99%), 15분 34초 동안 약 21.6MiB 상승. 초반
5분 정도는 분당 1~2MiB씩 오르다 후반으로 갈수록 증가 폭이 줄어드는 모양(플래토에 수렴하는
추세) — GR-154 micro soak(16분)에서 본 패턴과 비슷하다. 다만 15분은 짧아 "계속 오르는지
안정화된 것인지" 완전히 단정하긴 이르다.

**커넥션**: 전 구간 `active=0, idle=10, pending=0`으로 고정 — 커넥션 누수 징후 없음.

**배치 처리**: 15분 내내 백엔드 로그에 error/exception/OOM 없음.
`verify.sql` 결과:
- 밀린 주문(단계 간격 지나고도 안 넘어간 것) **0건**
- OUT_FOR_DELIVERY에서 멈춰 DELIVERED로 안 넘어간 주문 **0건**
- `orders.status`(DELIVERED)와 `shipments.delivered_at` 불일치 **0건**
- 전체 190건 중 175건 DELIVERED, 나머지 15건(LINE_HAUL_OUT 5·LINE_HAUL_IN 5·
  OUT_FOR_DELIVERY(SHIPPED) 5)은 soak 종료 직전 투입돼 아직 파이프라인을 타고 있는
  주문들 — 배치가 밀리지 않고 꾸준히 완료까지 처리했다는 뜻.

이번 soak는 `feed-shipments.sh`가 HTTP API를 거치지 않고 SQL로 직접 주문을 꽂아 넣는
방식이라, 애플리케이션 메모리에는 거의 부하를 주지 않는다(위 메모리 수치는 사실상 유휴
기준치 + 완만한 증가다) — "배치 자체가 몇 시간 동안 메모리를 누수 없이 처리하는지"는
확인했지만, 이 테스트 단독으로는 micro의 힙 여유(≈64MB, GR-154 result.md 참고)를
압박하는 수준은 아니다.

## 결론

- **완료조건 1(soak 시나리오 존재)**: 충족.
- **완료조건 2(장시간 실행 후 추이 기록)**: 15분 스모크로 메모리가 플래토에 수렴하는
  경향을 확인했으나, "장시간(2~4시간)"을 요구하는 본래 취지 대비로는 축소판이다.
  15분 안에서는 계속 오르는 패턴(누수 의심)이 아니라 증가 폭이 줄어드는 패턴이라 일단
  누수 징후는 없다고 보지만, 더 긴 시간(1시간 이상)으로 재검증하면 확신도가 올라간다.
- **완료조건 3(상태 중복·유실 여부 기록)**: 부분 충족 — `verify.sql`은 "밀린 주문",
  "OUT_FOR_DELIVERY에서 멈춘 주문", "최종 상태 불일치" 등 **현재 시점 기준 이상 징후**와
  완료 건수만 확인한다. 전이 이력 테이블이 없어 중간에 단계를 건너뛰거나 같은 단계를
  중복 처리한 뒤 최종적으로는 정상 상태로 보이는 경우는 이 쿼리로 잡아내지 못한다 —
  이번 15분 실행에서는 그런 징후를 관찰하지 못했다는 뜻이지, 중간 과정의 중복·유실
  가능성을 완전히 배제했다는 뜻은 아니다.

## 측정 환경의 한계

- 15분은 GR-172가 원래 요구한 "장시간(soak)"의 축소판이다 — 더 느린 누수는 이 시간
  안에서 포착되지 않았을 수 있다.
- `feed-shipments.sh`는 SQL 직접 삽입이라 애플리케이션 레이어(송장 등록 API 등) 부하는
  포함하지 않는다 — 순수 `ShipmentProgressScheduler` 배치 자체만 검증했다.
- VU 성격의 동시 요청 경쟁은 없다(단일 스케줄러 스레드, `FOR UPDATE SKIP LOCKED` 기반이라
  경쟁 자체가 설계상 발생하지 않음, GR-171과 같은 결론).

## 2026-10-27 측정: GR-191 인덱스(idx_shipments_stage_progress) 적용 전/후, 30만건

위 soak는 190건 규모라 `advanceStageBatch`(`ShipmentRepository`)가 받쳐주는 인덱스 없이도
느려지지 않았다. 실제로 데이터가 쌓였을 때 체감되는지 확인하려고 전용 드롭에 shipments
30만건(배치 대상 아닌 `OUT_FOR_DELIVERY` 29만건 + 대상 `COLLECTED`·오래된
`stage_updated_at` 1만건)을 일회성으로 시드하고, `idx_shipments_stage_progress`를
지웠다 만들었다 하며 `advanceStageBatch` 쿼리(LIMIT 500)를 트랜잭션 안에서
`EXPLAIN ANALYZE` 후 `ROLLBACK`으로 비교했다(측정 후 삭제, soak 본 시나리오 데이터와는
별개 드롭이라 영향 없음).

- 적용 전: `orders`를 `status='SHIPPED'`로 찾은 뒤(Seq Scan, 30만건 중 매치 포함)
  `shipments`도 `Seq Scan`으로 걸러 Hash Join — 캐시 워밍 후 **179~189ms**
- 적용 후: `shipments` 쪽이 `Bitmap Index Scan using idx_shipments_stage_progress`로
  바뀜(대상 1만건을 0.5~0.8ms에 찾음) — 캐시 워밍 후 **138~149ms**(약 25~30% 개선)

개선 폭이 GR-191의 orders 인덱스만큼 극적이진 않다 — 이 쿼리는 `orders.status='SHIPPED'`
`Seq Scan`과의 Hash Join이라, 전체 쿼리 시간의 상당 부분이 여전히 orders 쪽 스캔
(약 90ms, 30만건 중 상태 필터)에 들어간다. 이번 시드는 테스트 드롭의 주문을 전부
`SHIPPED`로 깔아(실제로는 있을 수 없는 비율) orders 쪽 셀렉티비티가 낮게 나온 것이라,
운영처럼 `SHIPPED` 비율이 훨씬 낮으면 orders 쪽도 인덱스를 타 전체 개선 폭이 더 커질
가능성이 높다(검증 안 함, 운영 분포로 추가 측정 필요).

**운영 DB 왕복(RTT) 반영**: 위 수치는 로컬 docker 네트워크(RTT 사실상 0)에서 잰
DB 실행시간만이다. `advanceStageBatch`는 스케줄러 주기당 DB 호출 1회라, 운영
Supabase psql 실측 RTT(3ms로 계산)를 더하면:
- 적용 전: 185ms(중간값) + 3ms(RTT) = **약 188ms**
- 적용 후: 140ms(중간값) + 3ms(RTT) = **약 143ms**
- 개선폭은 **약 24%**로 RTT를 더해도 비율은 거의 그대로다(쿼리 자체가 왕복 1회뿐이라
  RTT 비중이 작음).
