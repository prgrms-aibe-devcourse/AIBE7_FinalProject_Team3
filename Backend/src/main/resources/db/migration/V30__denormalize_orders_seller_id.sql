-- GR-214: 판매자 주문 목록 조회(dropId 미지정, OrderRepository.findSellerOrders)가 drops 조인 없이
-- 바로 인덱스를 타게 하는 비정규화 컬럼. V29(idx_orders_drop_created)는 dropId를 지정한 조회만
-- 커버했고, dropId 없이 판매자 전체 주문을 최신순으로 조회하는 가장 흔한 경로는 drop_id 선두
-- 컬럼 때문에 그 인덱스를 못 타 여전히 느렸다(실측: 15만건에서 list p95 1.1~1.3s).
-- 정합성의 근원은 drops.seller_id이고 이 컬럼은 조회 성능용 캐시라 FK는 걸지 않는다(판매자가
-- 삭제되는 경로가 없어 드롭도 함께 고아가 되지 않지만, FK 없이도 애플리케이션이 항상 drop과
-- 같은 값을 써서 일관성을 유지한다).
ALTER TABLE orders ADD COLUMN seller_id BIGINT;

UPDATE orders o
SET seller_id = d.seller_id
FROM drops d
WHERE d.id = o.drop_id;

-- NOT NULL을 걸지 않는다: 블루/그린 배포(deploy.sh) 중에는 신규 컨테이너가 이 마이그레이션을
-- 끝내고 헬스체크를 통과하기 전까지(최대 수십 초) 구버전 컨테이너가 여전히 라이브 트래픽을
-- 받는다. 그 구간에 NOT NULL까지 걸리면 구버전 코드(seller_id를 모르는 Order 엔티티)가
-- INSERT INTO orders를 할 때마다 실패한다. 애플리케이션(Order 엔티티, OrderCreateTransactionService)이
-- 항상 값을 채워 넣으므로 실질적으로는 비어있지 않지만, 배포 전환 구간의 안전을 위해 DB
-- 제약으로는 강제하지 않는다.
CREATE INDEX idx_orders_seller_created
    ON orders (seller_id, created_at, id);
