#!/bin/bash
# postgres 컨테이너의 송신에 고정 지연을 걸거나 푼다(GR-181).
# 운영은 DB가 외부(Supabase)라 요청마다 네트워크 왕복이 생기는데, 로컬 compose는 같은 머신이라 왕복이 거의 0이다.
# postgres가 보내는 패킷에 지연을 주면 backend가 보내는 쿼리마다 응답이 그만큼 늦어져, 왕복 시간이 늘어난 상황을 흉내 낸다.
# postgres 네트워크 네임스페이스에 붙는 일회용 컨테이너(NET_ADMIN)로 tc netem을 적용하며 postgres 컨테이너 자체는 바꾸지 않는다.
#
#   Backend/k6/drop-detail-scale/netem.sh 1     # 1ms 지연
#   Backend/k6/drop-detail-scale/netem.sh off   # 해제
#   Backend/k6/drop-detail-scale/netem.sh show  # 현재 상태
set -euo pipefail
if [ $# -ne 1 ]; then
  sed -n '7,9p' "$0"
  exit 1
fi
cd "$(dirname "$0")/../.."  # Backend
PG=$(docker compose ps -q postgres)

case "$1" in
  off) CMD='tc qdisc del dev eth0 root 2>/dev/null || true' ;;
  show) CMD='true' ;;
  *) CMD="tc qdisc replace dev eth0 root netem delay ${1}ms" ;;
esac
# sh -e로 단계마다 실패를 그대로 드러낸다. tc 설치가 실패하면 해제도 못 한 채 성공으로 끝나지 않게 한다.
docker run --rm --net "container:$PG" --cap-add NET_ADMIN alpine:3.20 sh -ec "
  apk add -q iproute2-tc > /dev/null 2>&1 || { echo 'iproute2-tc 설치 실패: 지연을 바꾸지 못했다' >&2; exit 1; }
  $CMD
  tc qdisc show dev eth0"
