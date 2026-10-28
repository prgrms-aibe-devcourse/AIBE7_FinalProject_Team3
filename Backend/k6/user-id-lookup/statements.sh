#!/bin/bash
# 요청 한 번이 DB에서 실행하는 문장 수를 센다(GR-201 M00-03). 대상은 내 정보 조회(me)와 내 주문 목록(orders)이다.
# 1) pg_stat_statements: 대상마다 통계를 비우고 요청을 N번 보내며 걸린 시간을 잰 뒤 쿼리별 calls를 모은다. 바로 이어서
#    요청 없이 같은 시간만큼 한 번 더 재서(배경) 쿼리별로 뺀다. 배경은 스케줄러(10초 주기)가 내는 쿼리다.
#    차감값은 음수까지 그대로 합산한다. 기준값 아래를 버리면 작은 음수는 사라지고 큰 양수만 남아 결과가 부풀기 때문이다.
#    대신 배경 구간의 전체 호출 수 ÷ N을 background_per_request로 따로 보여, 요청당 값에 섞일 수 있는 오차의 크기로 읽는다.
#    BEGIN은 집계되지만(track_utility 기본값 on) pgjdbc가 보내는 COMMIT은 집계되지 않아 트랜잭션 수만큼 더한다(아래 요약 계산 주석).
# 2) log_statement: me만 몇 번 보내는 동안 log_statement=all을 켜고 postgres 로그를 발췌한다. ID 변환이 별도 트랜잭션
#    (BEGIN·SELECT·COMMIT)이고 회원 조회가 또 하나의 트랜잭션인지 눈으로 확인하는 용도다. 수치는 1)을 쓴다.
#
# 요청 간격(INTERVAL_S)은 0.5초 미만으로 둔다. HikariCP는 500ms 넘게 쉰 연결을 빌려줄 때 살아 있는지 DB에 확인해
# 요청과 무관한 문장이 끼어든다.
# compose.loadtest-db-nano.yaml(pg_stat_statements 로드)을 합쳐 띄우고, 공통 계정 시드와 user-id-lookup/seed.sql을 넣은 뒤 실행한다.
# Windows Git Bash에서도 MSYS_NO_PATHCONV=1을 붙이지 않는다. 붙이면 Windows용 curl에 -o /dev/null이 그대로 넘어가 쓰기 오류(23)가 난다.
# log_statement는 ALTER SYSTEM으로 바꾸므로(데이터 볼륨의 postgresql.auto.conf에 남는다) 바꾸기 전의 ALTER SYSTEM 값을
# 기억했다가 끝나거나 실패하면 그 값으로 되돌린다. 되돌린 뒤 값이 처음과 다르면 실패로 알린다.
#
#   Backend/k6/user-id-lookup/statements.sh <이름> [요청 횟수]
#
# 결과: out/<이름>/statements/<대상>.tsv(쿼리별 calls·배경·요청당), summary.tsv(대상별 요청당 문장 수),
#       log-me.txt(문장 로그 발췌), postgres.log(발췌 전 postgres 로그 원본)
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '18p' "$0"
  exit 1
fi
NAME=$1; N=${2:-100}
BASE_URL=${BASE_URL:-http://localhost:8080}
INTERVAL_S=${INTERVAL_S:-0.1}
LOG_REQUESTS=${LOG_REQUESTS:-3}
EMAIL=${EMAIL:-k6-buyer@loadtest.local}
PASSWORD=${PASSWORD:-LoadTest1234!}

# 요청 수가 0이면 측정 요청 없이 진행하고 집계에서 0으로 나누게 되므로 시작 전에 거른다.
is_positive_int() { [[ "$1" =~ ^[1-9][0-9]*$ ]]; }
if ! is_positive_int "$N"; then
  echo "요청 횟수는 1 이상의 정수여야 한다: $N" >&2
  exit 1
fi
if ! is_positive_int "$LOG_REQUESTS"; then
  echo "LOG_REQUESTS는 1 이상의 정수여야 한다: $LOG_REQUESTS" >&2
  exit 1
fi

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/user-id-lookup
OUT=$DIR/out/$NAME/statements; rm -rf "$OUT"; mkdir -p "$OUT"
psql() { docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -qAt "$@"; }
JAR=$(mktemp)

# log_statement 복구용. 바꾸기 전 실제 값과, postgresql.auto.conf(ALTER SYSTEM)에 있던 값(없으면 빈 값)을 기억한다.
LOG_CHANGED=false
LOG_ORIGINAL=""
LOG_ORIGINAL_AUTO=""

restore_log() {
  if [ "$LOG_CHANGED" != true ]; then
    return 0
  fi
  if [ -n "$LOG_ORIGINAL_AUTO" ]; then
    psql -c "ALTER SYSTEM SET log_statement = '$LOG_ORIGINAL_AUTO'" > /dev/null || return 1
  else
    psql -c "ALTER SYSTEM RESET log_statement" > /dev/null || return 1
  fi
  psql -c "SELECT pg_reload_conf()" > /dev/null || return 1
  sleep 1
  local now
  now=$(psql -c "SHOW log_statement") || return 1
  if [ "$now" != "$LOG_ORIGINAL" ]; then
    echo "log_statement 복구 실패: 처음 $LOG_ORIGINAL, 지금 $now" >&2
    return 1
  fi
  LOG_CHANGED=false
}

on_exit() {
  local status=$?
  rm -f "$JAR"
  if ! restore_log; then
    echo "log_statement를 되돌리지 못했다. postgres에서 직접 확인한다(SHOW log_statement, pg_file_settings)" >&2
    status=1
  fi
  exit "$status"
}
trap on_exit EXIT

if ! psql -c "SHOW shared_preload_libraries" | grep -q pg_stat_statements; then
  echo "pg_stat_statements가 로드되지 않았다. compose.loadtest-db-nano.yaml을 합쳐 띄운다" >&2
  exit 1
fi
psql -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" 2> /dev/null > /dev/null

# orders 결과는 측정 계정의 주문이 seed.sql의 10건일 때의 쿼리 구성이다(목록 기본 페이지 20건보다 적어 전체 개수 쿼리 없음).
# 다른 시나리오가 같은 계정에 주문을 남겼으면 쿼리 구성이 달라지므로 시작 전에 멈춘다.
EXPECTED_BUYER_ORDERS=10
BUYER_ORDERS=$(psql -c "SELECT count(*) FROM orders o JOIN users u ON u.id = o.buyer_id WHERE u.email = '$EMAIL'")
if [ "$BUYER_ORDERS" != "$EXPECTED_BUYER_ORDERS" ]; then
  echo "$EMAIL의 주문이 ${BUYER_ORDERS}건이다(기대 ${EXPECTED_BUYER_ORDERS}건). user-id-lookup/seed.sql을 넣은 DB에서 다른 시나리오 주문 없이 잰다" >&2
  exit 1
fi
echo "$BUYER_ORDERS" > "$OUT/buyer-orders.txt"

# 서버는 XSRF-TOKEN 쿠키에 Secure를 붙여 curl이 http로 다시 보내지 않으므로 값을 꺼내 직접 싣는다(common/lib/auth.js와 같은 이유).
curl -sSf -c "$JAR" -o /dev/null "$BASE_URL/api/v1/auth/csrf"
XSRF=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR")
curl -sSf -c "$JAR" -b "XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' -o /dev/null \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" "$BASE_URL/api/v1/auth/login"
ACCESS=$(awk '$6 == "access_token" { print $7 }' "$JAR")
if [ -z "$ACCESS" ]; then
  echo "로그인 응답에 access_token이 없다" >&2
  exit 1
fi

path_of() {
  case "$1" in
    me) echo /api/v1/users/me ;;
    orders) echo /api/v1/orders ;;
  esac
}

# 대상 하나를 N번 보낸다.
send() {
  local target=$1 count=$2 path code
  path=$(path_of "$target")
  for _ in $(seq 1 "$count"); do
    code=$(curl -sS -o /dev/null -w '%{http_code}' -b "access_token=$ACCESS" "$BASE_URL$path")
    if [ "$code" != 200 ]; then
      echo "$target 응답 $code" >&2
      exit 1
    fi
    sleep "$INTERVAL_S"
  done
}

# 통계를 비운 시점부터 모은 시점까지 쿼리별 calls를 파일로 남긴다. 쿼리 원문의 줄바꿈·탭은 공백으로 바꾼다.
dump_stats() {
  psql -F $'\t' -c "SELECT queryid, calls, regexp_replace(query, '\s+', ' ', 'g')
                     FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'grab')
                       AND query NOT LIKE '%pg_stat_statements%' ORDER BY calls DESC" > "$1"
}

# 대상 구간을 재고, 그 구간이 실제로 걸린 시간만큼 배경 구간을 바로 이어서 잰다. 걸린 시간은 elapsed-<대상>.txt에 남긴다.
measure() {
  local target=$1 start end elapsed
  psql -c "SELECT pg_stat_statements_reset()" > /dev/null
  start=$(date +%s.%N)
  send "$target" "$N"
  end=$(date +%s.%N)
  dump_stats "$OUT/raw-$target.tsv"
  elapsed=$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.2f", e - s }')

  psql -c "SELECT pg_stat_statements_reset()" > /dev/null
  sleep "$elapsed"
  dump_stats "$OUT/raw-idle-$target.tsv"
  echo "$elapsed" > "$OUT/elapsed-$target.txt"
}

# 첫 호출에서만 생기는 일(커넥션 준비 등)을 빼려고 대상마다 몇 번 먼저 보낸다.
send me 5; send orders 5

echo "== pg_stat_statements: 대상 ${N}회(간격 ${INTERVAL_S}초) → 같은 시간만큼 배경, me·orders 순서"
measure me
measure orders

# pgjdbc는 COMMIT을 준비된 문장(execute S_n: COMMIT)으로 보내는데, pg_stat_statements에는 이 COMMIT이 집계되지 않는다
# (2026-10-28 확인: log_statement에는 트랜잭션마다 COMMIT이 찍히지만 통계에는 행이 없다). 그래서 COMMIT 행이 없으면
# 트랜잭션 수(BEGIN 계열 차감값 합)만큼 COMMIT을 더해 요청당 문장 수를 낸다. 더한 값은 commit_added 열에 따로 남긴다.
printf 'target\trequests\twindow_s\tcounted_per_request\tcommit_added\tstatements_per_request\ttransactions_per_request\tbackground_per_request\n' \
  > "$OUT/summary.tsv"
for TARGET in me orders; do
  node -e "
    const fs = require('fs'), n = $N;
    const read = (f) => fs.readFileSync(f, 'utf8').trim().split('\n').filter(Boolean)
      .map((l) => { const [id, calls, ...q] = l.split('\t'); return { id, calls: Number(calls), query: q.join(' ') }; });
    const target = read('$OUT/raw-$TARGET.tsv'), idle = read('$OUT/raw-idle-$TARGET.tsv');
    // 대상·배경 어느 한쪽에만 있는 쿼리도 빠짐없이 차감한다(없는 쪽은 0).
    const byId = new Map();
    for (const r of target) byId.set(r.id, { query: r.query, calls: r.calls, background: 0 });
    for (const r of idle) {
      const row = byId.get(r.id) || { query: r.query, calls: 0, background: 0 };
      row.background = r.calls;
      byId.set(r.id, row);
    }
    const rows = [...byId.values()].map((r) => ({ ...r, perRequest: (r.calls - r.background) / n }))
      .sort((a, b) => b.perRequest - a.perRequest);
    const isBegin = (q) => /^(BEGIN|START TRANSACTION)/i.test(q);
    const counted = rows.reduce((s, r) => s + r.perRequest, 0);
    const tx = rows.filter((r) => isBegin(r.query)).reduce((s, r) => s + r.perRequest, 0);
    const commitAdded = rows.some((r) => /^COMMIT/i.test(r.query)) ? 0 : tx;
    const backgroundPerRequest = idle.reduce((s, r) => s + r.calls, 0) / n;
    const lines = ['per_request\tcalls\tbackground\tquery',
      ...rows.map((r) => [r.perRequest.toFixed(2), r.calls, r.background, r.query.slice(0, 200)].join('\t'))];
    if (commitAdded !== 0) {
      lines.push([commitAdded.toFixed(2), '-', '-', 'COMMIT (집계되지 않아 트랜잭션 수만큼 더함)'].join('\t'));
    }
    fs.writeFileSync('$OUT/$TARGET.tsv', lines.join('\n') + '\n');
    const window = fs.readFileSync('$OUT/elapsed-$TARGET.txt', 'utf8').trim();
    fs.appendFileSync('$OUT/summary.tsv', ['$TARGET', n, window, counted.toFixed(2), commitAdded.toFixed(2),
      (counted + commitAdded).toFixed(2), tx.toFixed(2), backgroundPerRequest.toFixed(2)].join('\t') + '\n');
  "
  echo "-- $TARGET"
  column -t -s $'\t' "$OUT/$TARGET.tsv" | cut -c1-160
done

echo "== log_statement: me ${LOG_REQUESTS}회"
LOG_ORIGINAL=$(psql -c "SHOW log_statement")
LOG_ORIGINAL_AUTO=$(psql -c "SELECT setting FROM pg_file_settings
                             WHERE name = 'log_statement' AND sourcefile LIKE '%postgresql.auto.conf'
                             ORDER BY seqno DESC LIMIT 1")
LOG_CHANGED=true
psql -c "ALTER SYSTEM SET log_statement = 'all'" -c "SELECT pg_reload_conf()" > /dev/null
sleep 1
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
sleep 1
send me "$LOG_REQUESTS"
sleep 1
if ! restore_log; then
  exit 1
fi
# 요청 구간의 문장 로그만 남긴다. 줄 앞의 [pid]로 어느 커넥션(트랜잭션)에서 나갔는지 구분한다.
# 첫 요청 앞의 빈 statement는 1초 쉰 연결을 빌릴 때 HikariCP가 보내는 연결 확인이다(요청과 무관).
# 로그 조회 실패, 문장 로그 없음, ID 변환 조회가 요청 수보다 적게 찍힘은 트랜잭션 경계 확인 자료가 없는 것이라 실패로 끝낸다.
# 문장 수(1)) 결과는 그 전에 이미 파일로 남아 있다.
echo "log_statement 복구: $LOG_ORIGINAL"
echo "== 요약"
column -t -s $'\t' "$OUT/summary.tsv"

if ! docker compose logs --no-log-prefix --since "$SINCE" postgres > "$OUT/postgres.log" 2> "$OUT/postgres-log.err"; then
  echo "postgres 로그를 가져오지 못했다: $(head -c 300 "$OUT/postgres-log.err")" >&2
  exit 1
fi
grep -a -E 'LOG:  (statement|execute)' "$OUT/postgres.log" > "$OUT/log-me.txt" || true
if [ ! -s "$OUT/log-me.txt" ]; then
  echo "요청 구간(${SINCE} 이후) postgres 로그에 SQL 문장 로그가 없다. log_statement 적용 여부를 확인한다" >&2
  exit 1
fi
LOOKUPS=$(grep -a -c 'from users u1_0 where u1_0.public_id=' "$OUT/log-me.txt" || true)
if [ "$LOOKUPS" -lt "$LOG_REQUESTS" ]; then
  echo "로그에 ID 변환 조회가 ${LOOKUPS}번만 있다(요청 ${LOG_REQUESTS}번). 트랜잭션 경계를 확인할 수 없다" >&2
  exit 1
fi
echo "== log_statement 발췌: me ${LOG_REQUESTS}회(ID 변환 조회 ${LOOKUPS}번)"
cat "$OUT/log-me.txt"
