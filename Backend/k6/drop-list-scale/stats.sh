#!/bin/bash
# 측정 중 backend·postgres의 CPU·메모리와 cgroup 스왑 사용량을 2초 간격으로 기록한다(GR-164).
# 측정 명령 앞에 백그라운드로 띄우고, 끝나면 종료한다. 줄 앞은 epoch 초다.
#   Backend/k6/drop-list-scale/stats.sh <출력 파일> &
#   STATS=$!; ...측정...; kill $STATS
set -u
OUT=$1
cd "$(dirname "$0")/../.."  # Backend
BACKEND=$(docker compose ps -q backend)
POSTGRES=$(docker compose ps -q postgres)
while true; do
  NOW=$(date +%s)
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
    | grep -E 'backend-1|postgres-1' | sed "s/^/$NOW /"
  # backend는 재시작(explain.sh)되면 컨테이너 ID는 같고 프로세스만 바뀌므로 그대로 읽는다.
  echo "$NOW swap backend=$(docker exec "$BACKEND" cat /sys/fs/cgroup/memory.swap.current 2>/dev/null || echo down) postgres=$(docker exec "$POSTGRES" cat /sys/fs/cgroup/memory.swap.current 2>/dev/null || echo down)"
  sleep 2
done >> "$OUT"
