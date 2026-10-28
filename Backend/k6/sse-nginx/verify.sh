#!/bin/bash
# GR-180 Nginx 경유 검증(REALTIME_STOCK.md 13.3). nginx.sh로 띄운 로컬 Nginx(운영 grab.conf의 location 그대로)를 거쳐
# 아래 항목을 차례로 확인한다. 결과는 out/<항목>.json·txt에 남는다. 전달 지연·재조회 비교(버퍼링)는 sse-stream/run.sh를
# BASE_URL=http://localhost:8088로 따로 돌린다(k6/sse-nginx/result 참고).
#
#   1) idle      heartbeat를 90초로 늦추고 150초 유지 → 데이터 없는 구간 45초 이상에서 Nginx가 끊지 않는지
#   2) limit     Ubuntu 기본값 흉내(워커 2 × 768, nofile 1024)로 1000 연결 → 어디서 막히는지
#   3) limit-up  워커 연결 4096·nofile 8192로 1000 연결 → 전원 ready인지
#   4) reload    300 연결 유지 중 nginx reload(배포 전환) → 기존 연결이 끊기지 않는지, 이전 워커가 남는지
#   5) restart   300 연결 유지 중 backend 재시작(이전 컬러 종료) → 종료 안내·재접속·복구 시간
#
# compose 스택(COMPOSE_FILE)과 sse-stream/seed.sql이 준비된 상태여야 한다. backend를 다시 만들며 끝나면 기본값으로 되돌린다.
#   export COMPOSE_FILE=compose.yaml:compose.loadtest-micro.yaml:compose.loadtest-sse.yaml:compose.loadtest-db-nano.yaml
#   k6/sse-nginx/verify.sh            # 전부
#   k6/sse-nginx/verify.sh reload     # 하나만
set -u
if [ -z "${COMPOSE_FILE:-}" ]; then
  sed -n '12,14p' "$0"
  exit 1
fi
cd "$(dirname "$0")/../.."  # Backend
D=k6/sse-nginx; O=$D/out; mkdir -p $O
STEPS=${*:-idle limit limit-up reload restart}

# 재접속이 몰려도 구독 요청 제한(60초 75회, 부하용 1000회)에 먼저 걸리지 않게 크게 둔다. 이번 검증 대상이 아니다.
backend() {
  # --no-deps: postgres를 다시 만들지 않는다(netem 지연·CPU 한도 유지, stages.sh와 같은 이유).
  GRAB_STOCKSTREAM_CONNECTRATEMAX=100000 "$@" docker compose up -d --no-deps --force-recreate --wait backend > /dev/null 2>&1
  for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/drops?size=1)" = 200 ] && return 0
    sleep 2
  done
  echo "실패: backend 기동" >&2
  exit 1
}
clients() {  # <이름> <연결 수> <유지 초>
  BASE_URL=http://localhost:8088 CONNECTIONS=$2 DURATION_SEC=$3 RAMP_SEC=10 OUT=$O/$1.json \
    node $D/reconnect.mjs > $O/$1.txt 2>&1
}
summary() { node -e 'console.log(JSON.stringify(require(process.argv[1]).summary))' "$PWD/$O/$1.json"; }

for STEP in $STEPS; do
  echo "===== $STEP"
  case $STEP in
    idle)
      backend env GRAB_STOCKSTREAM_HEARTBEATINTERVAL=90s
      $D/nginx.sh up > $O/idle-nginx.txt
      clients idle 50 150
      summary idle
      ;;
    limit)
      backend env
      $D/nginx.sh up > $O/limit-nginx.txt
      clients limit 1000 60 &
      sleep 40; docker logs grab-sse-nginx 2>&1 | grep -E 'worker_connections|Too many open files' | sort | uniq -c | head -5 > $O/limit-nginx-errors.txt
      wait
      summary limit; cat $O/limit-nginx-errors.txt
      ;;
    limit-up)
      backend env
      WORKER_CONNECTIONS=4096 NOFILE=8192 $D/nginx.sh up > $O/limit-up-nginx.txt
      clients limit-up 1000 60
      summary limit-up
      docker logs grab-sse-nginx 2>&1 | grep -cE 'worker_connections|Too many open files' | sed 's/^/nginx 연결 부족 로그 /'
      ;;
    reload)
      backend env
      WORKER_CONNECTIONS=4096 NOFILE=8192 $D/nginx.sh up > /dev/null
      clients reload 300 120 &
      sleep 40; $D/nginx.sh reload; sleep 5
      $D/nginx.sh status > $O/reload-workers.txt  # 이전 워커는 'shutting down'으로 남는다
      sleep 30; $D/nginx.sh status > $O/reload-workers-35s.txt
      wait
      summary reload; cat $O/reload-workers.txt
      ;;
    restart)
      backend env
      WORKER_CONNECTIONS=4096 NOFILE=8192 $D/nginx.sh up > /dev/null
      clients restart 300 180 &
      # 배포 스크립트처럼 이전 backend를 종료한다(docker stop = SIGTERM, 종료 안내 후 정리). 단일 backend라 새로 뜰 때까지는 접속 불가다.
      sleep 40; docker compose stop backend > /dev/null 2>&1; docker compose start backend > /dev/null 2>&1
      wait
      summary restart
      ;;
  esac
done
$D/nginx.sh down > /dev/null
backend env
