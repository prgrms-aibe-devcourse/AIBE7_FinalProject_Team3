#!/usr/bin/env bash
# GR-154: payment-batch-soak.js 실행 중 메모리·CPU 추이를 기록한다.
# k6와 별도로 띄워둔다:
#   Backend/k6/payment-batch-soak/monitor-resources.sh backend-backend-1 60 > soak-resources.log &
CONTAINER="${1:-backend-backend-1}"
INTERVAL_SECONDS="${2:-60}"

while true; do
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $(docker stats "$CONTAINER" --no-stream --format '{{.CPUPerc}} {{.MemUsage}} {{.MemPerc}}')"
    sleep "$INTERVAL_SECONDS"
done
