# 운영 DB(Supabase Nano) 모사 환경 검증 (GR-186)

GR-180 통합 부하테스트의 최종 판정 환경을 만든다. 실제 Supabase·EC2에는 부하를 걸지 않기로 했으므로(2026-10-27 결정)
여기서 맞춘 조건과 맞추지 못한 조건이 GR-180 결과의 "측정 환경의 한계"가 된다.

## 구성

```
docker compose -f compose.yaml -f compose.loadtest-micro.yaml -f compose.loadtest-sse.yaml -f compose.loadtest-db-nano.yaml up -d --build
k6/drop-detail-scale/netem.sh 2.5   # 운영 실측 왕복(3.2~3.4ms)에 맞춘 값(아래 보정)
k6/db-nano/check.sh
```

| 항목 | 운영(Supabase Nano) | 모사 | 근거 |
|---|---|---|---|
| 메모리 | 최대 0.5GB | `mem_limit 512m`, 스왑 없음 | Supabase 문서 |
| CPU | 공유 CPU(코어 수·비율 비공개) | `cpus: 1` **가정값** (`GRAB_DB_NANO_CPUS`) | 맞출 수 없음 |
| 직접 연결 한도 | 60 | `max_connections=60` | Supabase 문서 |
| 풀러(Supavisor) 클라이언트 | 200 | 모사하지 않음 | 운영은 포트 5432(아래) |
| shared_buffers | 공개 값 없음 | 128MB(postgres 기본값 명시) | 메모리의 1/4 |
| DB 왕복 | 3.2~3.4ms(EC2에서 `psql \timing`, `SELECT 1`) | `netem.sh`로 postgres 송신 지연 | 아래 보정 |

Supabase 수치 출처: [Compute and Disk](https://supabase.com/docs/guides/platform/compute-and-disk) (2026-10-27 확인).

## 왕복 지연 보정

`check.sh`로 운영 실측과 같은 방식(같은 compose 네트워크의 psql, 한 세션에서 `SELECT 1` 반복, `\timing`)으로 쟀다.
Docker Desktop(macOS, 29.8.2), postgres 제한 적용 상태.

| netem | n | min | p50 | p95 | avg |
|---|---|---|---|---|---|
| 없음 | 200 | 0.020 | 0.059 | 0.131 | 0.067 |
| 2.5ms | 500 | 2.672 | 3.181 | 3.418 | 3.171 |
| 3ms | 500 | 3.139 | 3.872 | 4.138 | 3.867 |
| 3.3ms | 300 | 3.433 | 4.183 | 4.413 | 4.161 |

- netem 값에 psql·Docker 네트워크 오버헤드(약 0.2~0.9ms)가 더해진다. **netem 2.5ms가 운영 실측 범위(3.2~3.4ms)와 맞고**,
  3ms는 p50 기준 약 0.5ms(15%) 더 느린 보수적 조건이다.
- **GR-180은 netem 2.5ms로 판정한다**(2026-10-27 결정). 보수적 조건은 backend micro 제약과 DB vCPU 1개 가정에 이미 들어 있다.
- GR-181·183의 지연 주입 측정은 netem 3ms였으므로 그 결과와 GR-180 결과를 직접 비교하지 않는다.

## 운영 접속 경로

운영 앱 `DB_URL`의 포트는 **5432**다(6543 트랜잭션 풀러가 아님). 5432는 직접 연결(`db.<ref>.supabase.co`)과 세션 풀러(`*.pooler.supabase.com`)가
같이 쓰는 포트라 호스트는 확인하지 않았다. 두 경우 모두 backend 커넥션 하나가 세션 동안 DB 커넥션 하나를 쓰므로 prepared statement 동작은 로컬과 같고,
DB 쪽 한도는 직접 연결 60(`max_connections=60`으로 모사)이 기준이다. 세션 풀러라면 풀러를 한 번 더 거치는 왕복이 생기지만 운영 실측(3.2~3.4ms)도
같은 경로로 쟀다면 그 값에 이미 들어 있다. blue/green 전환 중에는 backend 두 개가 풀(Hikari 기본 10)을 동시에 열어 최대 20개를 쓴다.

## 한계

- 공유 CPU를 고정 `cpus`로 근사했다. 이웃 부하에 따른 변동, 버스트 크레딧 소진은 재현하지 않는다.
- 왕복 지연은 고정값이며 운영 네트워크의 지터·TLS 비용은 재현하지 않는다(운영 실측값에는 포함).
- 디스크 IO(Supabase 기본 gp3)는 제한하지 않는다. 측정 seed는 수십 MB라 shared_buffers 안에 대부분 들어간다.
