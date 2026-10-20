#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM_CONF=/etc/nginx/upstream.conf
IMAGE_TAG="${1:?사용법: deploy.sh <이미지-태그>}"

current_port=$(grep -oP 'server 127\.0\.0\.1:\K[0-9]+' "$UPSTREAM_CONF")
if [ "$current_port" = "8080" ]; then
    new_color=green
    new_port=8081
else
    new_color=blue
    new_port=8080
fi
old_color=$([ "$new_color" = "blue" ] && echo green || echo blue)

echo "현재 live: ${current_port} -> 신규 배포: ${new_color}(${new_port})"

IMAGE_TAG="$IMAGE_TAG" docker compose -f "$DEPLOY_DIR/compose.prod.yaml" --env-file "$DEPLOY_DIR/.env" up -d "backend-${new_color}"

echo "헬스체크 대기 중..."
healthy=false
for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${new_port}/actuator/health" | grep -q '"status":"UP"'; then
        healthy=true
        break
    fi
    sleep 2
done

if [ "$healthy" != true ]; then
    echo "헬스체크 실패, 배포 중단" >&2
    docker compose -f "$DEPLOY_DIR/compose.prod.yaml" logs --tail 100 "backend-${new_color}" >&2
    docker compose -f "$DEPLOY_DIR/compose.prod.yaml" stop "backend-${new_color}" || true
    exit 1
fi

echo "헬스체크 성공, nginx upstream을 ${new_color}(${new_port})로 전환"
upstream_backup=$(cat "$UPSTREAM_CONF")
restore_upstream() {
    echo "$upstream_backup" | sudo tee "$UPSTREAM_CONF" > /dev/null
}
trap restore_upstream EXIT

sudo sed -i "s/127\.0\.0\.1:[0-9]\+/127.0.0.1:${new_port}/" "$UPSTREAM_CONF"
sudo nginx -t
sudo systemctl reload nginx
trap - EXIT

# SSE(stock-stream)처럼 오래 붙어있는 연결이 nginx 전환 직후에도 이전 컬러에 남아있을 수 있어, 바로 끊지 않고 정리할 시간을 준다
echo "기존 컬러(${old_color}) 연결 정리 대기(30초)..."
sleep 30

docker compose -f "$DEPLOY_DIR/compose.prod.yaml" stop "backend-${old_color}"
docker compose -f "$DEPLOY_DIR/compose.prod.yaml" rm -f "backend-${old_color}"

echo "배포 완료: live=${new_color}(${new_port})"
