#!/usr/bin/env bash
# GR-154: payment-batch-soak.js 실행 중 메모리·CPU·DB 커넥션 추이를 기록한다.
# k6와 별도로 Backend/ 디렉터리에서 띄워둔다:
#   k6/payment-batch-soak/monitor-resources.sh backend-backend-1 60 > soak-resources.log &
CONTAINER="${1:-backend-backend-1}"
INTERVAL_SECONDS="${2:-60}"
PROMETHEUS_URL="${3:-http://localhost:8080/actuator/prometheus}"

while true; do
    STATS=$(docker stats "$CONTAINER" --no-stream --format '{{.CPUPerc}} {{.MemUsage}} {{.MemPerc}}')
    HIKARI=$(curl -s "$PROMETHEUS_URL" 2>/dev/null | awk '
        /^hikaricp_connections_active/ { active=$NF }
        /^hikaricp_connections_idle/ { idle=$NF }
        /^hikaricp_connections_pending/ { pending=$NF }
        END { printf "active=%s idle=%s pending=%s", active, idle, pending }
    ')
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $STATS $HIKARI"
    sleep "$INTERVAL_SECONDS"
done
