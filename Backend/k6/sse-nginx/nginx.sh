#!/bin/bash
# GR-180 Nginx 경유 검증용 로컬 Nginx. 운영 설정(deploy/nginx/grab.conf)의 location 블록을 그대로 가져와
# TLS만 뺀 server에 넣고, compose의 backend 앞에 띄운다. 운영 파일은 바꾸지 않는다.
#
# 운영 Nginx는 EC2 호스트(Ubuntu 패키지)에 설치돼 있어 nginx.conf(워커·연결 수)가 레포에 없다.
# 그래서 워커 설정은 Ubuntu 패키지 기본값을 흉내 낸 값을 기본으로 둔다:
#   worker_processes auto(t3.small vCPU 2개 → 2), worker_connections 768, systemd 기본 파일 디스크립터 한도(soft 1024).
# 운영 값이 다르면 환경변수로 바꿔 다시 잰다.
#
#   k6/sse-nginx/nginx.sh up                          # localhost:8088 → backend:8080
#   WORKER_CONNECTIONS=4096 NOFILE=8192 k6/sse-nginx/nginx.sh up
#   k6/sse-nginx/nginx.sh reload                      # 배포 전환(systemctl reload nginx) 흉내
#   k6/sse-nginx/nginx.sh status                      # 워커 프로세스(재시작 중인 이전 워커 포함)
#   k6/sse-nginx/nginx.sh down
set -euo pipefail
NAME=grab-sse-nginx
IMAGE=${NGINX_IMAGE:-nginx:1.24-alpine}  # Ubuntu 24.04 패키지 버전(1.24)에 맞춘다
WORKERS=${WORKERS:-2}
WORKER_CONNECTIONS=${WORKER_CONNECTIONS:-768}
NOFILE=${NOFILE:-1024}
cd "$(dirname "$0")/../.."  # Backend
OUT=k6/sse-nginx/out; mkdir -p $OUT

case "${1:-}" in
  up)
    # 443 server의 location 블록(SSE 경로 + 나머지)을 그대로 쓴다. 마지막 줄은 server 블록을 닫는 }라 뺀다.
    LOCATIONS=$(sed -n '/location \/api\/v1\/drops\/stock-stream/,$p' deploy/nginx/grab.conf | sed '$d')
    cat > $OUT/nginx.conf <<EOF
worker_processes $WORKERS;
events {
    worker_connections $WORKER_CONNECTIONS;
}
http {
    access_log off;
    keepalive_timeout 65;
    upstream backend_live {
        server backend:8080;
    }
    server {
        listen 80;
$LOCATIONS
    }
}
EOF
    docker rm -f $NAME > /dev/null 2>&1 || true
    docker run -d --name $NAME --network backend_default -p 8088:80 --ulimit nofile=$NOFILE:524288 \
      -v "$PWD/$OUT/nginx.conf:/etc/nginx/nginx.conf:ro" "$IMAGE" > /dev/null
    sleep 1
    docker exec $NAME nginx -v 2>&1
    echo "workers=$WORKERS worker_connections=$WORKER_CONNECTIONS nofile(soft)=$NOFILE → http://localhost:8088"
    ;;
  reload) docker exec $NAME nginx -s reload && echo reloaded ;;
  status) docker exec $NAME ps -o pid,etime,args | grep -E 'nginx: (master|worker)' ;;
  down) docker rm -f $NAME > /dev/null && echo removed ;;
  *) sed -n '9,13p' "$0"; exit 1 ;;
esac
