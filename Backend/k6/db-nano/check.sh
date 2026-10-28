#!/bin/bash
# compose.loadtest-db-nano.yaml 제한과 netem.sh 지연이 실제로 적용됐는지 확인한다(GR-186).
# 1) postgres 컨테이너 자원 한도  2) DB 설정값  3) 같은 compose 네트워크에서 잰 SELECT 1 왕복 시간 분포.
# 왕복은 일회용 psql 컨테이너(제한 없음)가 한 세션에서 SELECT 1을 반복하고 \timing 값을 모은다.
# 운영 실측(EC2 → Supabase, psql \timing, SELECT 1)과 같은 방식이라 그 값(3.2~3.4ms)과 직접 비교한다.
#
#   Backend/k6/db-nano/check.sh          # 200회
#   Backend/k6/db-nano/check.sh 1000     # 반복 횟수 지정
set -euo pipefail
N=${1:-200}
cd "$(dirname "$0")/../.."  # Backend
PG=$(docker compose ps -q postgres)
if [ -z "$PG" ]; then
  echo "postgres 컨테이너가 없다. compose를 먼저 띄운다" >&2
  exit 1
fi
NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$PG")
# compose가 .env의 DB_PASSWORD로 띄운 값을 그대로 쓴다. 셸 환경변수를 읽으면 .env 값과 어긋날 수 있다.
PASSWORD=$(docker compose exec -T postgres printenv POSTGRES_PASSWORD)

echo "== 자원 한도"
docker inspect -f 'memory={{.HostConfig.Memory}} memswap={{.HostConfig.MemorySwap}} nanocpus={{.HostConfig.NanoCpus}}' "$PG"

echo "== DB 설정"
docker compose exec -T postgres psql -U grab -d grab -Atc \
  "SELECT name || '=' || current_setting(name) FROM pg_settings
   WHERE name IN ('max_connections', 'shared_buffers', 'shared_preload_libraries') ORDER BY name"

echo "== 지연 설정"
k6/drop-detail-scale/netem.sh show

echo "== SELECT 1 왕복 ${N}회 (ms)"
# 첫 쿼리는 연결 직후 캐시 등으로 튀므로 버리고 나머지로 분포를 낸다.
{ echo '\timing on'; for _ in $(seq 0 "$N"); do echo 'SELECT 1;'; done; } \
  | docker run --rm -i --network "$NETWORK" -e PGPASSWORD="$PASSWORD" postgres:18 \
      psql -h postgres -U grab -d grab -qAt \
  | awk '/^Time:/ { print $2 }' | tail -n +2 | sort -n \
  | awk '{ v[NR] = $1; s += $1 }
         END { if (NR == 0) { print "측정값 없음"; exit 1 }
               printf "n=%d min=%.3f p50=%.3f p95=%.3f max=%.3f avg=%.3f\n",
                 NR, v[1], v[int(NR * 0.5 + 0.5)], v[int(NR * 0.95 + 0.5)], v[NR], s / NR }'
