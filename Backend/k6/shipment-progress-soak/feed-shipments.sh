#!/usr/bin/env bash
# GR-172: ShipmentProgressScheduler가 처리할 "평상시 수준의 배송 중 주문"을 soak 기간
# 내내 일정 주기로 투입한다(배치가 완료 처리해서 풀에서 빠지는 만큼 계속 채워줘야 장시간
# 부하가 유지된다). 이 배치는 HTTP 트리거가 없는 순수 DB 배치라(README 참고) k6 대신
# psql을 직접 반복 호출한다.
# 사전 준비: seed.sql 실행 후. Backend/ 디렉터리에서 실행:
#   k6/shipment-progress-soak/feed-shipments.sh
# 환경변수: DURATION_SECONDS(기본 7200=2시간), INTERVAL_SECONDS(기본 30), BATCH_SIZE(기본 5),
# RUN_ID(기본 현재 유닉스 타임스탬프 — soak를 재시작할 때 이전 실행과 order_number/
# idempotency_key가 겹치지 않게 한다. 같은 RUN_ID로 재실행하면 이전 실행의 투입분을
# 이어서 쓰는 것으로 간주한다).
# 메모리·커넥션 추이는 이 폴더의 monitor-resources.sh로 별도 기록한다.
set -euo pipefail

DURATION_SECONDS="${DURATION_SECONDS:-7200}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-30}"
BATCH_SIZE="${BATCH_SIZE:-5}"
RUN_ID="${RUN_ID:-$(date +%s)}"

deadline=$(( $(date +%s) + DURATION_SECONDS ))
tick=0
succeeded=0
failed=0

while [ "$(date +%s)" -lt "$deadline" ]; do
    tick=$((tick + 1))
    sql=$(cat <<SQL
WITH new_orders AS (
    INSERT INTO orders (
        order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
        product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
        total_amount, recipient_name, recipient_phone, postal_code, address_line1,
        payment_expires_at, paid_at
    )
    SELECT 'GR172-FEED-${RUN_ID}-${tick}-' || gs, b.id, d.id, 'idem-GR172-feed-${RUN_ID}-${tick}-' || gs,
           substr(md5('GR172-feed-${RUN_ID}-${tick}-' || gs) || md5(random()::text), 1, 64), 'SHIPPED',
           'k6 배송배치 롱런 상품', 'GRAB 판매자', 10000, 0, 10000,
           'k6 테스터', '01099990000', '06236', '서울시 강남구 테스트로 1',
           now() + interval '15 minutes', now() - interval '1 minute'
    FROM generate_series(1, ${BATCH_SIZE}) gs,
         (SELECT id FROM users WHERE email = 'k6-buyer@loadtest.local') b,
         (SELECT id FROM drops WHERE name = 'k6 배송배치 롱런 상품') d
    RETURNING id
)
INSERT INTO shipments (
    order_id, carrier_code, tracking_number, idempotency_key, request_hash,
    shipped_at, delivery_stage, stage_updated_at
)
SELECT id, 'CJ대한통운', 'TRACK-GR172-FEED-${RUN_ID}-${tick}-' || id, 'ship-idem-GR172-feed-${RUN_ID}-${tick}-' || id,
       substr(md5('GR172-ship-feed-${RUN_ID}-${tick}-' || id) || md5(random()::text), 1, 64),
       now(), 'COLLECTED', now()
FROM new_orders;
SQL
)
    # 장시간 무인 실행 중 일시적인 docker/psql 실패로 전체 soak가 중간에 멈추면 안 되므로,
    # 이 tick만 건너뛰고 다음 주기를 계속 돈다(set -e의 즉시 종료를 여기서만 막음).
    if docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab <<< "$sql"; then
        succeeded=$((succeeded + 1))
    else
        failed=$((failed + 1))
        echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) tick ${tick} 실패, 다음 주기로 계속 진행" >&2
    fi
    sleep "$INTERVAL_SECONDS"
done

echo "완료: 성공 ${succeeded}회 / 실패 ${failed}회(배치당 ${BATCH_SIZE}건, RUN_ID=${RUN_ID}). verify.sql로 상태 전이 정합성을 확인하세요."
if [ "$succeeded" -eq 0 ]; then
    echo "경고: 성공한 투입이 한 건도 없습니다 — docker/postgres 상태를 확인하세요." >&2
    exit 1
fi
