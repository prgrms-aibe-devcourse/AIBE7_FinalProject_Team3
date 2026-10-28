#!/bin/bash
# 목록 조회 동시 접속 단계별 측정(GR-164). 대상마다 concurrency.js를 돌리고, 단계 구간별 HikariCP·JVM 힙·GC 지표를 Prometheus에서
# 읽어 k6 지표와 한 표(summary.tsv)로 합친다. 측정 동안 stats.sh로 CPU·메모리·스왑을 함께 기록한다.
# JVM 열: 힙 최대 사용량, GC 멈춤 횟수·합계·최대, 할당 속도, 요청당 할당량(할당 속도 ÷ 처리량).
# seed.sql을 넣고 compose(+ compose.swapoff.yaml)를 띄운 상태에서 실행한다. explain.sh와 동시에 돌리지 않는다.
#
#   Backend/k6/drop-list-scale/concurrency.sh <이름> [대상 ...]
#   Backend/k6/drop-list-scale/concurrency.sh baseline mix grab_none date_soldout
#
# 다른 시나리오가 같은 실행기를 쓸 때는 SCRIPT(k6 디렉터리 기준 스크립트 경로)와 OUT_BASE(결과 상위 폴더)를 바꾼다(GR-181 상세·재고).
# 단계는 STEPS(기본 25,50,100,200,400,800), 단계 길이 STEP_S(기본 60초), 요청 후 대기 THINK_S(기본 1초)로 바꿀 수 있다.
# Prometheus 스크랩이 15초 간격이라 STEP_S를 45초보다 줄이면 HikariCP 지표가 비거나 부정확해진다.
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '7,8p' "$0"
  exit 1
fi
NAME=$1; shift
TARGETS=${*:-mix grab_none date_soldout}
STEPS=${STEPS:-25,50,100,200,400,800}
STEP_S=${STEP_S:-60}
GAP_S=${GAP_S:-10}
THINK_S=${THINK_S:-1}
PROMETHEUS=${PROMETHEUS:-http://localhost:9090}
SCRIPT=${SCRIPT:-drop-list-scale/concurrency.js}

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/drop-list-scale
OUT=${OUT_BASE:-$DIR/out}/$NAME/concurrency; rm -rf "$OUT"; mkdir -p "$OUT"

"$DIR/stats.sh" "$PWD/$OUT/stats.txt" &
STATS=$!
trap 'kill $STATS 2>/dev/null; pkill -f "drop-list-scale/stats.sh" 2>/dev/null || true' EXIT

# 구간 끝 시각 기준으로 지난 STEP_S초를 본다. 값이 없으면 none.
q() {
  curl -s "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1" --data-urlencode "time=$2" \
    | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const r=JSON.parse(s).data?.result?.[0];
      const v=r?Number(r.value[1]):NaN; console.log(Number.isFinite(v)?v.toFixed(2):'none')})"
}

printf 'target\tvus\treqs\trps\tmed_ms\tp95_ms\tp99_ms\tfail_rate\thikari_active_max\thikari_pending_max\tacquire_avg_ms\tacquire_max_ms\thikari_timeouts\theap_max_mb\tgc_count\tgc_pause_sum_ms\tgc_pause_max_ms\talloc_mb_s\talloc_kb_per_req\n' \
  > "$OUT/summary.tsv"

for TARGET in $TARGETS; do
  echo "== $TARGET =="
  docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 \
    -e BASE_URL=http://backend:8080 -e TARGET="$TARGET" -e STEPS="$STEPS" -e STEP_S="$STEP_S" -e GAP_S="$GAP_S" -e THINK_S="$THINK_S" \
    grafana/k6 run --quiet --summary-export "${OUT#k6/}/$TARGET-k6.json" \
    "$SCRIPT" > "$OUT/$TARGET-stdout.txt" 2>&1
  START=$(grep -o 'K6_SCENARIO_START=[0-9]*' "$OUT/$TARGET-stdout.txt" | head -1 | cut -d= -f2)
  sleep 20  # 마지막 단계 구간이 Prometheus에 스크랩될 때까지 기다린다.

  I=0
  for VUS in ${STEPS//,/ }; do
    END=$((START + I * (STEP_S + GAP_S) + STEP_S))
    W="${STEP_S}s"
    K6=$(node -e "
      const m = require('./$OUT/$TARGET-k6.json').metrics, s = 'scenario:vu$VUS';
      const d = m['http_req_duration{' + s + '}'], n = m['http_reqs{' + s + '}'], f = m['http_req_failed{' + s + '}'];
      console.log([n.count, (n.count / $STEP_S).toFixed(1), d.med.toFixed(1), d['p(95)'].toFixed(1), d['p(99)'].toFixed(1), f.value.toFixed(4)].join('\t'));")
    RPS=$(echo "$K6" | cut -f2)
    ALLOC=$(q "sum(rate(jvm_gc_memory_allocated_bytes_total[$W]))/1048576" $END)
    PER_REQ=$(node -e "const a = Number('$ALLOC'), r = Number('$RPS'); console.log(a > 0 && r > 0 ? (a * 1024 / r).toFixed(0) : 'none')")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$TARGET" "$VUS" "$K6" \
      "$(q "max_over_time(hikaricp_connections_active[$W])" $END)" \
      "$(q "max_over_time(hikaricp_connections_pending[$W])" $END)" \
      "$(q "1000*increase(hikaricp_connections_acquire_seconds_sum[$W])/increase(hikaricp_connections_acquire_seconds_count[$W])" $END)" \
      "$(q "1000*max_over_time(hikaricp_connections_acquire_seconds_max[$W])" $END)" \
      "$(q "increase(hikaricp_connections_timeout_total[$W])" $END)" \
      "$(q "max_over_time(sum(jvm_memory_used_bytes{area=\"heap\"})[$W:5s])/1048576" $END)" \
      "$(q "sum(increase(jvm_gc_pause_seconds_count[$W]))" $END)" \
      "$(q "1000*sum(increase(jvm_gc_pause_seconds_sum[$W]))" $END)" \
      "$(q "1000*max(max_over_time(jvm_gc_pause_seconds_max[$W]))" $END)" \
      "$ALLOC" "$PER_REQ" >> "$OUT/summary.tsv"
    I=$((I + 1))
  done
done

column -t -s $'\t' "$OUT/summary.tsv"
