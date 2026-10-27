#!/bin/bash
# DB 왕복 지연을 주입한 상태로 상세·재고 응답시간과 동시 접속을 측정한다(GR-181).
# 운영(EC2 → Supabase)의 네트워크 왕복을 netem.sh로 흉내 내고, measure.sh와 같은 순서(워밍업 → detail.js → 워밍업 → 동시 접속)로 잰다.
# 쿼리 수·실행 계획은 지연과 무관해 다시 수집하지 않는다(measure.sh 결과 사용). 끝나거나 실패하면 지연을 반드시 해제한다.
# targets.sh를 먼저 실행한 상태여야 한다.
#
#   Backend/k6/drop-detail-scale/latency.sh <지연 ms> [동시 접속 대상 ...]
#   Backend/k6/drop-detail-scale/latency.sh 1 pair_hot stock_hot     # 결과: out/delay-1ms/
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '7,8p' "$0"
  exit 1
fi
DELAY=$1; shift
TARGETS=${*:-pair_hot stock_hot}
NAME=delay-${DELAY}ms

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/drop-detail-scale
OUT=$DIR/out/$NAME; mkdir -p "$OUT"
k6() {
  docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 -e BASE_URL=http://backend:8080 grafana/k6 "$@"
}

# 끝나거나 실패해도 통계 수집기를 멈추고 지연을 해제한다. 해제를 확인하지 못하면 다음 측정이 오염되므로 경고를 남긴다.
cleanup() {
  pkill -f "drop-list-scale/stats.sh" 2>/dev/null || true
  if ! "$DIR/netem.sh" off | grep -q noqueue; then
    echo "경고: postgres 지연 해제를 확인하지 못했다. 'netem.sh show'로 확인한다" >&2
  fi
}
trap cleanup EXIT
"$DIR/netem.sh" "$DELAY" > "$OUT/netem.txt"

echo "== ${DELAY}ms: 워밍업 + 조합별 응답시간"
k6 run --quiet drop-detail-scale/warmup.js > /dev/null 2>&1
k6/drop-list-scale/stats.sh "$PWD/$OUT/stats.txt" & STATS=$!
k6 run --quiet --summary-export "drop-detail-scale/out/$NAME/k6-summary.json" drop-detail-scale/detail.js \
  > "$OUT/k6-stdout.txt" 2>&1
kill "$STATS" 2>/dev/null || true

echo "== ${DELAY}ms: 워밍업 + 동시 접속 ($TARGETS)"
k6 run --quiet drop-detail-scale/warmup.js > /dev/null 2>&1
SCRIPT=drop-detail-scale/detail-concurrency.js OUT_BASE=$DIR/out k6/drop-list-scale/concurrency.sh "$NAME" $TARGETS > /dev/null

echo "== ${DELAY}ms 완료"
column -t -s $'\t' "$OUT/concurrency/summary.tsv" | cut -c1-140
