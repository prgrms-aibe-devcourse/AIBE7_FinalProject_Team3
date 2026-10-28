#!/bin/bash
# 요청 한 번이 DB와 몇 번 왕복하는지 잰다(GR-201 M00-03). 대상은 내 정보 조회(me)와 내 주문 목록(orders)이다.
# netem.sh로 postgres 송신에 고정 지연을 걸면 DB 왕복마다 응답이 그만큼 늦어진다. DB 처리 시간은 그대로라
# (지연 있을 때 응답시간 중앙값 − 지연 없을 때 중앙값) ÷ 지연 = 요청당 왕복 수다.
# 문장 수(statements.sh)와 다를 수 있다. pgjdbc는 BEGIN을 첫 문장과 묶어 한 번에 보내기 때문이다.
#
# 요청은 사용자 1명(curl 순차)으로 보내고, 간격(INTERVAL_S)은 0.5초 미만으로 둔다. HikariCP는 500ms 넘게 쉰 연결을
# 빌려줄 때 살아 있는지 DB에 확인해 왕복이 하나 늘어난다. 조건마다 몇 번 먼저 보내 예열한 뒤 잰다.
# 시작 전 netem 상태(지연 값 또는 없음)를 기억했다가 끝나거나 실패하면 그 상태로 되돌리고, 되돌린 상태가 다르면 실패로 알린다.
# compose.loadtest-db-nano.yaml을 합쳐 띄우고, 공통 계정 시드와 user-id-lookup/seed.sql을 넣은 뒤 실행한다.
# Windows Git Bash에서도 MSYS_NO_PATHCONV=1을 붙이지 않는다. 붙이면 Windows용 curl에 -o /dev/null이 그대로 넘어가 쓰기 오류(23)가 난다.
#
#   Backend/k6/user-id-lookup/roundtrips.sh <이름> [요청 횟수]
#
# 결과: out/<이름>/roundtrips/<대상>-<지연>ms.txt(요청별 응답시간 ms), summary.tsv(대상별 중앙값·추정 왕복 수)
set -euo pipefail
if [ $# -lt 1 ]; then
  sed -n '13p' "$0"
  exit 1
fi
NAME=$1; N=${2:-30}
DELAY_MS=${DELAY_MS:-10}
WARMUP=${WARMUP:-5}
BASE_URL=${BASE_URL:-http://localhost:8080}
INTERVAL_S=${INTERVAL_S:-0.1}
EMAIL=${EMAIL:-k6-buyer@loadtest.local}
PASSWORD=${PASSWORD:-LoadTest1234!}

is_positive_int() { [[ "$1" =~ ^[1-9][0-9]*$ ]]; }
for pair in "N:$N" "DELAY_MS:$DELAY_MS" "WARMUP:$WARMUP"; do
  if ! is_positive_int "${pair#*:}"; then
    echo "${pair%%:*}는 1 이상의 정수여야 한다: ${pair#*:}" >&2
    exit 1
  fi
done

cd "$(dirname "$0")/../.."  # Backend
DIR=k6/user-id-lookup
NETEM=k6/drop-detail-scale/netem.sh
OUT=$DIR/out/$NAME/roundtrips; rm -rf "$OUT"; mkdir -p "$OUT"
JAR=$(mktemp)

# netem.sh show 출력에서 지연 값(ms)을 꺼낸다. 지연이 없으면 빈 값이다.
current_delay() {
  local shown
  shown=$("$NETEM" show) || return 1
  if [[ "$shown" =~ delay\ ([0-9.]+)ms ]]; then
    echo "${BASH_REMATCH[1]}"
  fi
}

set_delay() {
  if [ -z "$1" ]; then
    "$NETEM" off > /dev/null
  else
    "$NETEM" "$1" > /dev/null
  fi
}

# orders 결과는 측정 계정의 주문이 seed.sql의 10건일 때의 쿼리 구성이다(목록 기본 페이지 20건보다 적어 전체 개수 쿼리 없음).
# 다른 시나리오가 같은 계정에 주문을 남겼으면 왕복 수가 달라지므로 시작 전에 멈춘다.
EXPECTED_BUYER_ORDERS=10
BUYER_ORDERS=$(docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -qAt \
  -c "SELECT count(*) FROM orders o JOIN users u ON u.id = o.buyer_id WHERE u.email = '$EMAIL'")
if [ "$BUYER_ORDERS" != "$EXPECTED_BUYER_ORDERS" ]; then
  echo "$EMAIL의 주문이 ${BUYER_ORDERS}건이다(기대 ${EXPECTED_BUYER_ORDERS}건). user-id-lookup/seed.sql을 넣은 DB에서 다른 시나리오 주문 없이 잰다" >&2
  exit 1
fi
echo "$BUYER_ORDERS" > "$OUT/buyer-orders.txt"

NETEM_CHANGED=false
NETEM_ORIGINAL=$(current_delay) || { echo "netem 상태를 읽지 못했다" >&2; exit 1; }

restore_netem() {
  if [ "$NETEM_CHANGED" != true ]; then
    return 0
  fi
  set_delay "$NETEM_ORIGINAL" || return 1
  local now
  now=$(current_delay) || return 1
  if [ "$now" != "$NETEM_ORIGINAL" ]; then
    echo "netem 복구 실패: 처음 '${NETEM_ORIGINAL:-없음}', 지금 '${now:-없음}'" >&2
    return 1
  fi
  NETEM_CHANGED=false
}

on_exit() {
  local status=$?
  rm -f "$JAR"
  if ! restore_netem; then
    echo "netem을 되돌리지 못했다. $NETEM show로 직접 확인한다" >&2
    status=1
  fi
  exit "$status"
}
trap on_exit EXIT

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

# 대상을 count번 보내고 요청별 응답시간(ms)을 출력한다. 200이 아니면 멈춘다.
send() {
  local target=$1 count=$2 path result
  path=$(path_of "$target")
  for _ in $(seq 1 "$count"); do
    result=$(curl -sS -o /dev/null -w '%{http_code} %{time_total}' -b "access_token=$ACCESS" "$BASE_URL$path")
    if [ "${result%% *}" != 200 ]; then
      echo "$target 응답 ${result%% *}" >&2
      exit 1
    fi
    awk -v t="${result#* }" 'BEGIN { printf "%.3f\n", t * 1000 }'
    sleep "$INTERVAL_S"
  done
}

# 지연 하나를 걸고 두 대상을 잰다. 지연이 실제로 걸렸는지 netem 상태로 확인한다.
run_condition() {
  local delay=$1 label=$2 applied
  set_delay "$delay"
  applied=$(current_delay)
  if [ "$applied" != "$delay" ]; then
    echo "지연이 적용되지 않았다: 기대 '${delay:-없음}', 실제 '${applied:-없음}'" >&2
    exit 1
  fi
  for TARGET in me orders; do
    send "$TARGET" "$WARMUP" > /dev/null
    send "$TARGET" "$N" > "$OUT/$TARGET-${label}ms.txt"
  done
}

NETEM_CHANGED=true
echo "== 지연 없음: me·orders 각 ${N}회(예열 ${WARMUP}회, 간격 ${INTERVAL_S}초)"
run_condition "" 0
echo "== 지연 ${DELAY_MS}ms: me·orders 각 ${N}회"
run_condition "$DELAY_MS" "$DELAY_MS"
if ! restore_netem; then
  exit 1
fi
echo "netem 복구: ${NETEM_ORIGINAL:-없음}"

printf 'target\trequests\tdelay_ms\tmedian_0_ms\tp25_0_ms\tp75_0_ms\tmedian_delay_ms\tp25_delay_ms\tp75_delay_ms\tround_trips\n' \
  > "$OUT/summary.tsv"
for TARGET in me orders; do
  node -e "
    const fs = require('fs');
    const read = (f) => fs.readFileSync(f, 'utf8').trim().split('\n').map(Number).sort((a, b) => a - b);
    const q = (xs, p) => { const i = (xs.length - 1) * p, lo = Math.floor(i), hi = Math.ceil(i); return xs[lo] + (xs[hi] - xs[lo]) * (i - lo); };
    const base = read('$OUT/$TARGET-0ms.txt'), delayed = read('$OUT/$TARGET-${DELAY_MS}ms.txt');
    const roundTrips = (q(delayed, 0.5) - q(base, 0.5)) / $DELAY_MS;
    fs.appendFileSync('$OUT/summary.tsv', ['$TARGET', base.length, $DELAY_MS,
      ...[0.5, 0.25, 0.75].map((p) => q(base, p).toFixed(2)),
      ...[0.5, 0.25, 0.75].map((p) => q(delayed, p).toFixed(2)),
      roundTrips.toFixed(2)].join('\t') + '\n');
  "
done

echo "== 요약"
column -t -s $'\t' "$OUT/summary.tsv"
