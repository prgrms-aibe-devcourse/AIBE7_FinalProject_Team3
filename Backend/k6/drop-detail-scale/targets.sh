#!/bin/bash
# 상세·재고 측정 대상 DROP을 고른다(GR-181). seed를 다시 넣으면 ID가 바뀌므로 측정 직전에 같은 규칙으로 다시 고른다.
#   hot    판매 중(GRAB, 판매 기간 안)이고 가용 재고가 있으며 SKU 4개(옵션 그룹 2개)인 상품 중 활성 WISH가 가장 많은 것.
#          드롭 오픈 직후 사람이 몰리는 인기 상품 상세를 대표한다.
#   simple 판매 중이고 가용 재고가 있는 SKU 1개(옵션 그룹 없음) 상품 중 id가 가장 작은 것. 가장 가벼운 상세다.
#   pool   seed의 공개 DROP(WISH·GRAB·ENDED) 전체. 여러 상품에 흩어진 조회를 흉내 낸다.
# 결과는 out/targets.json(gitignore)에 쓰고 k6 스크립트가 읽는다.
#   Backend/k6/drop-detail-scale/targets.sh
set -euo pipefail
cd "$(dirname "$0")/../.."  # Backend
OUT=k6/drop-detail-scale/out; mkdir -p "$OUT"

docker compose exec -T postgres psql -U grab -d grab -v ON_ERROR_STOP=1 -tA <<'SQL' > "$OUT/targets.json"
WITH seed AS (
    SELECT d.id, d.status, d.sale_starts_at, d.sale_ends_at,
           (SELECT COUNT(*) FROM drop_options o WHERE o.drop_id = d.id) AS skus,
           (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0)
              FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) AS available,
           (SELECT COUNT(*) FROM wishes w WHERE w.drop_id = d.id AND w.canceled_at IS NULL) AS wishes
    FROM drops d
    WHERE d.name LIKE 'k6 대용량 %' AND d.status IN ('WISH', 'GRAB', 'ENDED')
), selling AS (
    SELECT * FROM seed
    WHERE status = 'GRAB' AND sale_starts_at <= now() AND sale_ends_at > now() AND available > 0
)
SELECT json_build_object(
    'hot', (SELECT id FROM selling WHERE skus = 4 ORDER BY wishes DESC, id LIMIT 1),
    'hotWishes', (SELECT wishes FROM selling WHERE skus = 4 ORDER BY wishes DESC, id LIMIT 1),
    'simple', (SELECT id FROM selling WHERE skus = 1 ORDER BY id LIMIT 1),
    'pool', (SELECT json_agg(id ORDER BY id) FROM seed)
);
SQL
node -e "const t = require('./$OUT/targets.json');
  if (!t.hot || !t.simple || !t.pool?.length) { console.error('대상 선정 실패: seed.sql을 먼저 실행한다'); process.exit(1); }
  console.log('hot', t.hot, '(WISH ' + t.hotWishes + ')', 'simple', t.simple, 'pool', t.pool.length);"
