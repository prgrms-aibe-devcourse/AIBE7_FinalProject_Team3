#!/bin/bash
# GR-180 단계별 측정. 캐시 모드 하나로 연결 수를 차례로 올리며 run.sh를 REFETCH=1로 돌린다.
# 회차마다 backend를 새로 띄우고(같은 캐시 모드), 시드 → 워밍업(주문 20/초 × 60초) → run.sh 순서로 잰다.
# 연결 0은 SSE 비활성 기준선(주문만)이다. 기준값·트래픽 구성은 GR-180 이슈 "고정 기준값"을 따른다.
#
# compose 파일 조합은 COMPOSE_FILE로 넘긴다(backend를 다시 만들 때 같은 제한을 써야 하므로). netem 지연은 postgres에
# 걸려 있어 backend를 다시 만들어도 유지된다. 시작 전에 걸어 두고 끝나면 직접 해제한다.
#   export COMPOSE_FILE=compose.yaml:compose.loadtest-micro.yaml:compose.loadtest-sse.yaml:compose.loadtest-db-nano.yaml
#   k6/drop-detail-scale/netem.sh 2.5
#   k6/sse-stream/stages.sh none 0 200 400 600 800 1000      # 결과: out/none-<연결 수>-*
#   GRAB_STOCKSTREAM_MAXCONNECTIONS=1500 k6/sse-stream/stages.sh memory 1200 1500
#   TAG=pool20 GRAB_DB_POOL_SIZE=20 k6/sse-stream/stages.sh memory 400   # 조건을 바꾼 회차는 TAG로 이름을 구분한다(out/memory-pool20-400-*)
set -u
if [ $# -lt 2 ] || [ -z "${COMPOSE_FILE:-}" ]; then
  sed -n '8,11p' "$0"
  exit 1
fi
MODE=$1; shift
RATE=${RATE:-50}; DUR=${DUR:-180}
cd "$(dirname "$0")/../.."  # Backend
O=k6/sse-stream/out; mkdir -p $O
FAIL=0

for CONNS in "$@"; do
  NAME=$MODE${TAG:+-$TAG}-$CONNS
  echo "===== [$NAME] backend 재기동 (GRAB_DROP_CACHE_STORE=$MODE)"
  # --no-deps: postgres 설정(cpus 등)이 기본값과 다르면 compose가 postgres까지 다시 만들어 netem 지연과 CPU 한도가 사라진다.
  GRAB_DROP_CACHE_STORE=$MODE docker compose up -d --no-deps --force-recreate --wait backend > $O/up-$NAME.txt 2>&1 \
    || { echo "실패: backend 기동 ($O/up-$NAME.txt)"; exit 1; }
  # backend 서비스에는 헬스체크가 없어 --wait가 앱 기동을 기다리지 않는다. 공개 목록이 응답할 때까지 기다린다.
  for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/drops?size=1)" = 200 ] && break
    sleep 2
  done
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -q -f - < k6/sse-stream/seed.sql > /dev/null \
    || { echo "실패: seed.sql"; exit 1; }
  RATE=20 DURATION=60s k6 run -q k6/sse-stream/order-load.js > $O/warmup-$NAME.txt 2>&1 \
    || echo "주의: 워밍업 k6 종료 코드 $? ($O/warmup-$NAME.txt)"
  # run.sh가 시작할 때 out/<이름>-*를 지우므로 이 스크립트의 기록은 접두어를 앞에 둔다.
  REFETCH=1 k6/sse-stream/run.sh "$NAME" "$CONNS" "$RATE" "$DUR" > $O/summary-$NAME.txt 2>&1 || FAIL=1
  grep -E '^주문 p|checks_failed|전달 지연|hikari_(pending|timeouts)|CPU 평균|OOMKilled|^실패' $O/summary-$NAME.txt
done
exit $FAIL
