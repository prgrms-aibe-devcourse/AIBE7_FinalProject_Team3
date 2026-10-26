# DB 커넥션 풀 고갈 (connection-pool-exhaustion, GR-156)

> 공통 실행 환경·전제는 `../README.md` 참고.

## 2026-10-26 재검증: `memswap_limit` 추가(스왑 차단) + 검증 보강(threshold, 422 사유 확인) 후에도 통과

`memswap_limit: 512m`으로 스왑을 막고, `pool_exhaustion_failures_total` threshold와
422 사유(`INSUFFICIENT_STOCK`) 검증을 추가한 뒤 재실행. 체크 1810/1810(100%)
성공, threshold `count==0` 통과, HikariCP `pending=0`·`timeout_total=0`(acquire
최대 1.2s) — **통과**. 메모리 464MiB/512MiB(90.63%)까지 올라갔지만 OOM-kill
없음. 같은 메모리 압박 조건에서 [drop-open-spike](../drop-open-spike/result.md)는
죽었으므로, 이 시나리오의 통과가 nano 전체의 여유를 의미하지는 않는다 — 실제로
90%대는 다섯 시나리오 중 가장 높은 축이라 빠듯한 편이다.

HikariCP 풀 크기는 `application.yml`에 미지정 → 기본값 **10**. 주문 쓰기(`order_burst`,
VU 15)와 목록/상세 읽기(`read_burst`, VU 15)를 합쳐 풀 크기의 3배(30 VU)를 20초간
동시에 걸었다. 재고는 10000개로 넉넉히 둬서 재고부족이 아니라 순수 커넥션 경합만
보게 했다. **통과**.

- 체크 1894/1894(100%) 성공, `http_req_failed` 0.00%, 5xx **0건**
- 응답시간: med 196ms, p90 790ms, p95 1.09s, 최대 **5.27s** — 운영 캐파시티
  수치 아님, 공통 전제와 동일. `--out json`으로 재실행해 느린 요청 상위권을
  뜯어보니 전부 `POST /api/v1/orders`(201)였다. 목록/상세 읽기는 단순 SELECT라
  가볍지만, 주문 생성은 `SELECT ... FOR UPDATE`로 재고 행을 잠그고 주문·주문항목·
  재고예약을 INSERT하는 트랜잭션 전체를 커넥션 하나로 묶어 처리한다. VU 15명이
  같은 옵션 행에 동시에 락을 걸려고 경쟁해 **행 잠금 경합**이 지연의 유력한
  원인으로 보이며, 아래 HikariCP 지표(타임아웃 0건, pending 0)를 보면 순수
  커넥션 풀 고갈이 원인은 아니었다
- `/actuator/prometheus` HikariCP 지표(서버 기동 후 누적치라 이번 실행 구간만의
  값은 아님, 참고용): `hikaricp_connections_max=10`,
  `hikaricp_connections_acquire_seconds_max=0.81s`, `hikaricp_connections_timeout_total=0`,
  측정 시점 `hikaricp_connections_pending=0` — 커넥션 획득 대기는 최대 0.81초에
  그쳤고 타임아웃 없이 전부 처리됨. 기본 `connectionTimeout`(30초)에는 전혀 못 미침
- 백엔드 로그(ERROR/Exception/Timeout) 0건
- `verify.sql`: 재고 정합성 위반(초과판매·음수재고·예약+판매가 총수량 초과) 0행,
  생성 주문 252건(같은 buyer 계정으로 반복 주문해 일부는 비즈니스 규칙상 422를
  받았을 수 있으나 체크 기준은 201/422 모두 정상으로 봄 — 범위 밖인 재고 경쟁 로직)

## 결론

풀 크기(기본 10)의 3배 VU로도 타임아웃·5xx **미발생**. 요청이 큐에서 최대 0.81초
대기했을 뿐 커넥션 타임아웃(30초)에는 한참 못 미쳐, 현재 트래픽 규모에서는 풀
크기 10이 당장 병목이 아니다. 다만 이는 nano 하한선·단일 더미 DROP 기준이라
운영 트래픽 규모에서도 같다고 단정할 수 없다 — 풀 크기 조정은 범위 밖(팀 의사결정
필요).
