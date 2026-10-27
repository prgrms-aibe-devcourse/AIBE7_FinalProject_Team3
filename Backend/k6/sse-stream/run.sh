#!/bin/bash
# GR-157 SSE 부하테스트 한 회차를 처음부터 끝까지 실행한다.
# seed → docker stats 수집 → SSE 연결 유지(Node) → 주문 투입(k6) → Prometheus 지표 → verify.sql → 전달 지연 계산.
# 스펙 파일(compose.loadtest-nano/micro.yaml)과 compose.loadtest-sse.yaml을 합쳐 띄운 상태여야 한다(k6/README.md). 로컬 node·k6·docker가 필요하다.
#
#   Backend/k6/sse-stream/run.sh <이름> <SSE 연결 수> <주문/초> <주문 시간(초)>
#   Backend/k6/sse-stream/run.sh r0 0 50 300     # SSE 비활성 기준선
#   Backend/k6/sse-stream/run.sh r2 200 50 300   # 피크
#
# 결과는 out/<이름>-*.{txt,json,log}에 남는다. 주문 p95는 <이름>-k6.json의 "http_req_duration{name:order}".
# 시드 실패는 즉시 중단하고, 그 밖의 실패(k6 threshold, SSE 준비 수 부족, verify.sql 위반)는 끝까지 기록한 뒤 종료 코드 1로 알린다.
set -u
FAIL=0
fail() { echo "실패: $1"; FAIL=1; }
if [ $# -ne 4 ]; then
  sed -n '6,8p' "$0"
  exit 1
fi
NAME=$1; CONNS=$2; RATE=$3; DUR=$4
PROMETHEUS=${PROMETHEUS:-http://localhost:9090}
if [ "$DUR" -lt 60 ]; then
  echo "주의: 주문 시간이 60초 미만이면 Prometheus 증가량 지표(15초 스크랩)가 none으로 나올 수 있다."
fi

cd "$(dirname "$0")/../.."  # Backend
O=k6/sse-stream/out; mkdir -p $O
# k6 --console-output은 기존 파일 뒤에 이어 쓰므로, 같은 이름으로 다시 돌릴 때 이전 결과가 섞이지 않게 지운다.
rm -f $O/$NAME-*

if ! docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/sse-stream/seed.sql > $O/$NAME-seed.txt 2>&1; then
  echo "실패: seed.sql ($O/$NAME-seed.txt)"
  exit 1
fi

BACKEND=$(docker compose ps -q backend)
# backend·postgres CPU/메모리와 backend cgroup 스왑 사용량을 3초 간격으로 남긴다. 줄 앞은 epoch 초.
( while true; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}} {{.MemPerc}}' \
      | grep -E 'backend-1|postgres-1' | sed "s/^/$(date +%s) /"
    echo "$(date +%s) backend-swap $(docker exec $BACKEND cat /sys/fs/cgroup/memory.swap.current 2>/dev/null || echo down)"
    sleep 3
  done ) > $O/$NAME-stats.txt &
STATS=$!
trap 'kill $STATS 2>/dev/null; wait $STATS 2>/dev/null' EXIT

if [ "$CONNS" -gt 0 ]; then
  # 주문 시간 + 램프업 대기(30초) + 마지막 알림 여유(15초)만큼 연결을 유지한다.
  CONNECTIONS=$CONNS RAMP_SEC=20 DURATION_SEC=$((DUR + 45)) OUT=$O/$NAME-sse.json \
    node k6/sse-stream/sse-clients.mjs > $O/$NAME-sse.txt 2>&1 &
  SSE=$!
  sleep 30
fi

echo "[$NAME] 주문 ${RATE}/초 × ${DUR}초 시작 (SSE 연결 $CONNS)"
START=$(date +%s)
RATE=$RATE DURATION=${DUR}s k6 run -q --summary-export=$O/$NAME-k6.json \
  --console-output=$O/$NAME-orders.log k6/sse-stream/order-load.js > $O/$NAME-k6.txt 2>&1 \
  || fail "k6 종료 코드 $? (threshold 위반 등, $O/$NAME-k6.txt)"
END=$(date +%s)

if [ "$CONNS" -gt 0 ]; then
  wait $SSE
  # 고정 대기(30초) 안에 일부 연결이 429·503 등으로 준비되지 못했으면 지연 표본이 줄어든 회차라 실패로 본다.
  READY=$(node -e 'console.log(require(process.argv[1]).summary.ready)' "$PWD/$O/$NAME-sse.json" 2>/dev/null || echo 0)
  [ "$READY" = "$CONNS" ] || fail "SSE 준비 ${READY}/${CONNS} ($O/$NAME-sse.txt)"
fi
kill $STATS 2>/dev/null; wait $STATS 2>/dev/null
sleep 20  # Prometheus 스크랩 간격(15초)만큼 기다려야 마지막 구간이 반영된다.

W=$((END - START))s
q() {
  curl -s "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1" --data-urlencode "time=$((END + 15))" \
    | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s).data.result;console.log(r.length?Number(r[0].value[1]).toFixed(4):"none")})'
}
{
  echo "window=$W start=$START end=$END"
  echo "hikari_active_max=$(q "max_over_time(hikaricp_connections_active[$W])")"
  echo "hikari_pending_max=$(q "max_over_time(hikaricp_connections_pending[$W])")"
  echo "hikari_acquire_avg_ms=$(q "1000*increase(hikaricp_connections_acquire_seconds_sum[$W])/increase(hikaricp_connections_acquire_seconds_count[$W])")"
  echo "hikari_acquire_max_ms=$(q "1000*max_over_time(hikaricp_connections_acquire_seconds_max[$W])")"
  echo "hikari_acquire_count=$(q "increase(hikaricp_connections_acquire_seconds_count[$W])")"
  echo "hikari_timeouts=$(q "increase(hikaricp_connections_timeout_total[$W])")"
  echo "process_cpu_avg=$(q "avg_over_time(process_cpu_usage[$W])")"
  echo "process_cpu_max=$(q "max_over_time(process_cpu_usage[$W])")"
  echo "sse_connections_max=$(q "max_over_time(grab_stock_stream_connections[$W])")"
  for m in signals merged sent failures timeouts queue_rejected; do
    echo "sse_${m}=$(q "increase(grab_stock_stream_${m}_total[$W])")"
  done
} > $O/$NAME-prom.txt

docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f - < k6/sse-stream/verify.sql > $O/$NAME-verify.txt 2>&1 \
  || fail "verify.sql 재고 정합성 위반 또는 SQL 오류 ($O/$NAME-verify.txt)"
[ "$CONNS" -gt 0 ] && node k6/sse-stream/analyze.mjs $O/$NAME-sse.json $O/$NAME-orders.log > $O/$NAME-latency.json

# 요약 출력
node -e '
const s = require(process.argv[1]).metrics["http_req_duration{name:order}"] || {};
console.log(`주문 p50 ${s.med?.toFixed(1)}ms / p95 ${s["p(95)"]?.toFixed(1)}ms / 최대 ${s.max?.toFixed(1)}ms`);
' "$PWD/$O/$NAME-k6.json"
grep -E 'checks_failed|http_req_failed' $O/$NAME-k6.txt
[ "$CONNS" -gt 0 ] && echo "전달 지연: $(tr -d ' \n' < $O/$NAME-latency.json)"
cat $O/$NAME-prom.txt
awk '/backend-swap/{if($3=="down")down=1; else if($3>sw)sw=$3} END{printf "backend 스왑 최대 %.1fMiB%s\n", sw/1048576, down?" (측정 중 컨테이너 응답 없음)":""}' $O/$NAME-stats.txt
docker inspect $BACKEND --format 'backend 상태 {{.State.Status}}, OOMKilled={{.State.OOMKilled}}, 재시작 {{.RestartCount}}회'
docker exec $BACKEND sh -c 'grep -E "^oom_kill " /sys/fs/cgroup/memory.events' 2>/dev/null | sed 's/^/cgroup /'
awk '/backend-1/{gsub("%","",$3); gsub("%","",$7); c+=$3; n++; if($3>m)m=$3; if($7>mm)mm=$7}
     END{printf "backend CPU 평균 %.1f%% / 최대 %.1f%%, 메모리 최대 %.1f%%\n", c/n, m, mm}' $O/$NAME-stats.txt
echo "결과: $O/$NAME-*"
exit $FAIL
