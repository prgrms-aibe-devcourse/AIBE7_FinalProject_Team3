#!/bin/bash
# 공개 DROP 목록 조합별 실제 실행 계획을 수집한다(GR-164).
# 앱이 실제로 보내는 SQL·바인딩 값 그대로의 계획을 얻기 위해 psql로 쿼리를 다시 쓰지 않고 auto_explain을 쓴다.
# 실행 동안만 auto_explain(ANALYZE·BUFFERS)을 켜고 backend를 재시작해 커넥션을 새로 맺게 하며, 끝나면 설정을 되돌리고 다시 재시작한다.
# seed.sql을 넣은 뒤 docker compose로 띄운 상태에서 실행한다.
#
#   Backend/k6/drop-list-scale/explain.sh <이름> [반복 횟수]
#   Backend/k6/drop-list-scale/explain.sh baseline 5
#
# 조합은 combos.json, 결과는 out/<이름>/explain/<조합>-{main,count}.txt(마지막 회차 계획)와 summary.tsv(회차별 소요 시간 중앙값)다.
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '7,8p' "$0"
  exit 1
fi
NAME=$1; RUNS=${2:-5}
BASE_URL=${BASE_URL:-http://localhost:8080}

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/drop-list-scale
OUT=$DIR/out/$NAME/explain; rm -rf "$OUT"; mkdir -p "$OUT"
psql() { docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -q "$@"; }

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

# auto_explain.* 는 모듈을 올린 세션에서만 인식되므로 LOAD 후 ALTER SYSTEM 한다.
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

printf 'combo\tkind\tmedian_ms\truns_ms\trows\n' > "$OUT/summary.tsv"
for COMBO in $(node -e "require('./$DIR/combos.json').forEach(c => console.log(c.name))"); do
  QS=$(node -e "
    const c = require('./$DIR/combos.json').find(c => c.name === '$COMBO');
    console.log(new URLSearchParams({ ...c.params, page: '0', size: '20' }).toString());")
  curl -sf "$BASE_URL/api/v1/drops?$QS" > /dev/null  # 워밍업(캐시·커넥션)
  sleep 1.1  # docker logs --since는 초 단위라 워밍업 로그와 겹치지 않게 한다
  SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  for _ in $(seq 1 "$RUNS"); do
    curl -sf "$BASE_URL/api/v1/drops?$QS" > /dev/null
  done
  sleep 1
  # auto_explain 로그 한 건은 "duration: … plan:" 줄부터 다음 로그 머리줄 전까지다.
  # 상태 전환·결제 배치 쿼리도 섞이므로 목록 본문("thumbnailUrl")·count 쿼리만 고른다.
  docker compose logs postgres --since "$SINCE" --no-log-prefix 2>/dev/null | awk -v out="$OUT" -v combo="$COMBO" '
    function flush() {
      if (buf == "") return
      kind = ""
      if (buf ~ /"thumbnailUrl"/) kind = "main"
      else if (buf ~ /SELECT COUNT\(\*\)/ && buf ~ /JOIN categories c/) kind = "count"
      if (kind != "") {
        n[kind]++
        file = out "/" combo "-" kind ".txt"
        printf "%s", buf > file; close(file)
        match(header, /duration: [0-9.]+ ms/)
        d = substr(header, RSTART + 10, RLENGTH - 13)
        ms[kind] = ms[kind] (ms[kind] == "" ? "" : ",") d
        if (match(buf, /\(actual time=[^)]* rows=[0-9]+/)) {
          r = substr(buf, RSTART, RLENGTH); sub(/.*rows=/, "", r); rows[kind] = r
        }
      }
      buf = ""
    }
    /duration: [0-9.]+ ms +plan:/ { flush(); header = $0; buf = $0 "\n"; next }
    /^[0-9]{4}-[0-9]{2}-[0-9]{2} / { flush(); next }
    buf != "" { buf = buf $0 "\n" }
    END {
      flush()
      for (k in ms) print combo "\t" k "\t" ms[k] "\t" rows[k]
    }' | while IFS=$'\t' read -r C K MS ROWS; do
      MED=$(node -e "const a='$MS'.split(',').map(Number).sort((x,y)=>x-y); console.log(a[Math.floor(a.length/2)].toFixed(3))")
      printf '%s\t%s\t%s\t%s\t%s\n' "$C" "$K" "$MED" "$MS" "$ROWS" >> "$OUT/summary.tsv"
    done
  echo "$COMBO 완료"
done

column -t -s $'\t' "$OUT/summary.tsv"
