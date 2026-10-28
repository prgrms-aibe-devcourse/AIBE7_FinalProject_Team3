#!/bin/bash
# 상세·재고 기준선 한 회차를 처음부터 끝까지 실행한다(GR-181).
# 대상 선정 → 쿼리 수·실행 계획(explain.sh) → 워밍업 → 조합별 응답시간(detail.js) → 워밍업 → 동시 접속(concurrency.sh).
# explain.sh가 끝날 때 backend를 재시작하므로, 이후 단계는 항상 "재시작 + 같은 워밍업" 상태에서 시작한다.
# drop-list-scale/seed.sql을 넣고 compose(+ drop-list-scale/compose.swapoff.yaml)를 띄운 상태에서 실행한다.
#
#   Backend/k6/drop-detail-scale/measure.sh <이름> [동시 접속 대상 ...]
#   Backend/k6/drop-detail-scale/measure.sh baseline pair_hot pair_random stock_hot
#
# 결과는 out/<이름>/ 아래 explain/, k6-summary.json, stats.txt, concurrency/에 남는다(gitignore).
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '7,8p' "$0"
  exit 1
fi
NAME=$1; shift
TARGETS=${*:-pair_hot pair_random stock_hot}

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/drop-detail-scale
OUT=$DIR/out/$NAME; mkdir -p "$OUT"
trap 'pkill -f "drop-list-scale/stats.sh" 2>/dev/null || true' EXIT  # 중간에 실패해도 통계 수집기를 남기지 않는다
k6() {
  docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 -e BASE_URL=http://backend:8080 grafana/k6 "$@"
}

echo "== 대상 선정"; "$DIR/targets.sh"
echo "== 실행 계획"; "$DIR/explain.sh" "$NAME" 5 > /dev/null

echo "== 워밍업 + 조합별 응답시간"
k6 run --quiet drop-detail-scale/warmup.js > /dev/null 2>&1
k6-drop-list-stats() { k6/drop-list-scale/stats.sh "$PWD/$OUT/stats.txt"; }
k6-drop-list-stats & STATS=$!
k6 run --quiet --summary-export "drop-detail-scale/out/$NAME/k6-summary.json" drop-detail-scale/detail.js \
  > "$OUT/k6-stdout.txt" 2>&1
kill "$STATS" 2>/dev/null || true

echo "== 워밍업 + 동시 접속 ($TARGETS)"
k6 run --quiet drop-detail-scale/warmup.js > /dev/null 2>&1
SCRIPT=drop-detail-scale/detail-concurrency.js OUT_BASE=$DIR/out k6/drop-list-scale/concurrency.sh "$NAME" $TARGETS > /dev/null

echo "== 완료"
column -t -s $'\t' "$OUT/explain/summary.tsv"
column -t -s $'\t' "$OUT/concurrency/summary.tsv" | cut -c1-140
