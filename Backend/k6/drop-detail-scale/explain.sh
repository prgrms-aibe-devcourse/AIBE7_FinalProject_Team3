#!/bin/bash
# 상세·재고 요청 한 번이 보내는 쿼리 수와 쿼리별 실행 계획을 수집한다(GR-181).
# 목록(drop-list-scale/explain.sh)과 달리 상세는 요청당 쿼리가 여러 개라, 요청 구간의 auto_explain 로그를 모두 모은 뒤
# 상태 전환·결제 배치 쿼리를 걸러내고 남은 SELECT를 요청 수로 나눠 요청당 쿼리 수·시간·버퍼를 구한다.
# 실행 동안만 auto_explain(ANALYZE·BUFFERS)을 켜고 backend를 재시작하며, 끝나면 설정을 되돌리고 다시 재시작한다.
# targets.sh를 먼저 실행한다.
#
#   Backend/k6/drop-detail-scale/explain.sh <이름> [요청 횟수]
#
# 결과: out/<이름>/explain/<대상>.txt(마지막 요청의 계획), summary.tsv(대상별 요청당 쿼리 수·시간·버퍼), queries.tsv(쿼리 종류별)
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '9p' "$0"
  exit 1
fi
NAME=$1; RUNS=${2:-5}
BASE_URL=${BASE_URL:-http://localhost:8080}

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/drop-detail-scale
OUT=$DIR/out/$NAME/explain; rm -rf "$OUT"; mkdir -p "$OUT"
psql() { docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -q "$@"; }
HOT=$(node -e "console.log(require('./$DIR/out/targets.json').hot)")
SIMPLE=$(node -e "console.log(require('./$DIR/out/targets.json').simple)")

wait_backend() {
  for _ in $(seq 1 60); do
    curl -sf "$BASE_URL/api/v1/categories" > /dev/null && return 0
    sleep 2
  done
  echo "backend가 준비되지 않았다" >&2; exit 1
}

reset_explain() {
  psql <<'SQL'
ALTER SYSTEM RESET session_preload_libraries;
ALTER SYSTEM RESET auto_explain.log_min_duration;
ALTER SYSTEM RESET auto_explain.log_analyze;
ALTER SYSTEM RESET auto_explain.log_buffers;
SELECT pg_reload_conf();
SQL
  docker compose restart backend > /dev/null 2>&1
  wait_backend
}

psql <<'SQL'
LOAD 'auto_explain';
ALTER SYSTEM SET session_preload_libraries = 'auto_explain';
ALTER SYSTEM SET auto_explain.log_min_duration = 0;
ALTER SYSTEM SET auto_explain.log_analyze = on;
ALTER SYSTEM SET auto_explain.log_buffers = on;
SELECT pg_reload_conf();
SQL
trap reset_explain EXIT
docker compose restart backend > /dev/null 2>&1
wait_backend

printf 'target\tqueries_per_req\tms_per_req\tbuffers_per_req\n' > "$OUT/summary.tsv"
printf 'target\tquery\tcount_per_req\tms_per_req\tbuffers_per_req\n' > "$OUT/queries.tsv"
for SPEC in "detail_hot /api/v1/drops/$HOT" "detail_simple /api/v1/drops/$SIMPLE" \
            "stock_hot /api/v1/drops/$HOT/stocks" "stock_simple /api/v1/drops/$SIMPLE/stocks"; do
  TARGET=${SPEC%% *}; URL_PATH=${SPEC#* }
  curl -sf "$BASE_URL$URL_PATH" > /dev/null  # 워밍업
  sleep 1.1  # docker logs --since는 초 단위라 워밍업 로그와 겹치지 않게 한다
  SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  for _ in $(seq 1 "$RUNS"); do
    curl -sf "$BASE_URL$URL_PATH" > /dev/null
  done
  sleep 1
  docker compose logs postgres --since "$SINCE" --no-log-prefix 2>/dev/null > "$OUT/$TARGET.log"
  # auto_explain 한 건은 "duration: … plan:" 줄부터 다음 로그 머리줄 전까지다. 상세·재고 경로는 잠금 없는 SELECT뿐이므로
  # SELECT가 아니거나 배치가 쓰는 테이블·조건(잠금, 결제, 배송, 알림, 예약, 수요, 판매 시각 비교)이 있으면 제외한다.
  node - "$OUT" "$TARGET" "$RUNS" <<'JS'
const fs = require('fs');
const [out, target, runs] = process.argv.slice(2);
const lines = fs.readFileSync(`${out}/${target}.log`, 'utf8').split('\n');
const blocks = [];
let cur = null;
for (const l of lines) {
  if (/duration: [0-9.]+ ms +plan:/.test(l)) { if (cur) blocks.push(cur); cur = { head: l, body: [] }; continue; }
  if (/^\d{4}-\d{2}-\d{2} /.test(l)) { if (cur) blocks.push(cur); cur = null; continue; }
  if (cur) cur.body.push(l);
}
if (cur) blocks.push(cur);
const exclude = /for update|payment|shipment|notification|reservation|demand|sale_starts_at\s*<=|sale_ends_at\s*<=/i;
const picked = blocks.map((b) => {
  const text = b.body.join('\n');
  const q = (text.match(/Query Text:\s*([\s\S]*?)\n\s*(?:Limit|Seq Scan|Index|Bitmap|Nested|Hash|Sort|Aggregate|Result|Merge|Unique|Gather)/) || [, ''])[1]
    .replace(/\s+/g, ' ').trim();
  return { q, ms: Number(b.head.match(/duration: ([0-9.]+) ms/)[1]), buf: Number((text.match(/Buffers: shared hit=(\d+)/) || [, 0])[1]), text };
}).filter((b) => /^select/i.test(b.q) && !exclude.test(b.q));
const n = Number(runs);
const sum = (a, k) => a.reduce((s, x) => s + x[k], 0);
fs.appendFileSync(`${out}/summary.tsv`, [target, (picked.length / n).toFixed(1), (sum(picked, 'ms') / n).toFixed(3), (sum(picked, 'buf') / n).toFixed(0)].join('\t') + '\n');
const byQuery = {};
for (const b of picked) {
  const key = b.q.replace(/\$\d+|\b\d+\b/g, '?').slice(0, 140);
  (byQuery[key] ??= []).push(b);
}
for (const [k, v] of Object.entries(byQuery)) {
  fs.appendFileSync(`${out}/queries.tsv`, [target, k, (v.length / n).toFixed(1), (sum(v, 'ms') / n).toFixed(3), (sum(v, 'buf') / n).toFixed(0)].join('\t') + '\n');
}
// 마지막 요청분(전체의 1/n)을 계획 원문으로 남긴다.
fs.writeFileSync(`${out}/${target}.txt`, picked.slice(-Math.round(picked.length / n)).map((b) => b.head + '\n' + b.text).join('\n\n'));
JS
  rm -f "$OUT/$TARGET.log"
  echo "$TARGET 완료"
done

column -t -s $'\t' "$OUT/summary.tsv"
