#!/bin/bash
# postgres 컨테이너의 송신에 고정 지연을 걸거나 푼다(GR-181).
# 운영은 DB가 외부(Supabase)라 요청마다 네트워크 왕복이 생기는데, 로컬 compose는 같은 머신이라 왕복이 거의 0이다.
# postgres가 보내는 패킷에 지연을 주면 backend가 보내는 쿼리마다 응답이 그만큼 늦어져, 왕복 시간이 늘어난 상황을 흉내 낸다.
# postgres 네트워크 네임스페이스에 붙는 일회용 컨테이너(NET_ADMIN)로 tc netem을 적용하며 postgres 컨테이너 자체는 바꾸지 않는다.
#
#   Backend/k6/drop-detail-scale/netem.sh 1     # 1ms 지연
#   Backend/k6/drop-detail-scale/netem.sh off   # 해제
#   Backend/k6/drop-detail-scale/netem.sh show  # 현재 상태
#   NETEM_SERVICE=redis Backend/k6/drop-detail-scale/netem.sh 3   # 다른 compose 서비스(예: Redis, GR-183)에 건다
set -euo pipefail
if [ $# -ne 1 ]; then
  sed -n '7,9p' "$0"
  exit 1
fi
cd "$(dirname "$0")/../.."  # Backend
PG=$(docker compose ps -q "${NETEM_SERVICE:-postgres}")

case "$1" in
  off) CMD='tc qdisc del dev eth0 root 2>/dev/null || true' ;;
  show) CMD='true' ;;
  *) CMD="tc qdisc replace dev eth0 root netem delay ${1}ms" ;;
esac
# tc가 들어 있는 로컬 이미지를 쓴다. 실행할 때마다 패키지를 받으면 인터넷이 끊겼을 때 지연을 걸지도 풀지도 못한다.
# 이미지가 없으면 한 번만 만든다(이때만 인터넷이 필요하다). sh -e로 단계마다 실패를 그대로 드러낸다.
IMAGE=grab-netem:local
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  printf 'FROM alpine:3.20\nRUN apk add --no-cache iproute2-tc\n' | docker build -q -t "$IMAGE" - > /dev/null
fi
docker run --rm --net "container:$PG" --cap-add NET_ADMIN "$IMAGE" sh -ec "
  $CMD
  tc qdisc show dev eth0"
