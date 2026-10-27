-- GR-157 검증: 재고 정합성과 GR-163 비교용 쿼리 호출 수를 확인한다.
--   docker compose exec -T postgres psql -U grab -d grab -f - < Backend/k6/sse-stream/verify.sql

-- 1) 음수재고·초과판매 여부(0행 기대, stock-race/verify.sql과 동일 조건).
SELECT o.id AS option_id, o.total_quantity, o.reserved_quantity, o.sold_quantity, o.withheld_quantity
FROM drop_options o
JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 SSE 상품 %'
  AND (
        o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity
        OR o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
      );

-- 2) 생성된 주문 수. 아래 3)의 호출 수를 이 값으로 나눠 "주문 1건당 쿼리 수"를 구한다.
SELECT COUNT(*) AS created_orders
FROM orders o JOIN drops d ON d.id = o.drop_id
WHERE d.name LIKE 'k6 SSE 상품 %';

-- 3) 공개 여부 검증 COUNT 쿼리(countPublicDropsForSubscription) 호출 수.
--    구독 시점 1회(연결 수만큼)만 남는다. GR-163 이전에는 전송 시점 연결마다 1회가 더해졌다.
--    전송 시점 호출 수 = calls - 연결 성공 수(sse-clients.mjs 요약의 ready 수). GR-163 이후 0이어야 한다.
SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms, query
FROM pg_stat_statements
WHERE query ILIKE 'select count(%from drops%status<>%'
ORDER BY calls DESC;

-- 4) 참고: 호출 수 상위 쿼리. 3)이 비어 있으면 Hibernate가 만든 SQL 모양이 달라진 것이니 여기서 찾는다.
SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, left(query, 120) AS query
FROM pg_stat_statements
ORDER BY calls DESC
LIMIT 10;

-- 5) 1)에 행이 있으면 오류로 끝낸다. run.sh는 ON_ERROR_STOP=1로 실행해 회차 실패로 처리한다(위 결과는 이미 출력된 뒤다).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM drop_options o
        JOIN drops d ON d.id = o.drop_id
        WHERE d.name LIKE 'k6 SSE 상품 %'
          AND (
                o.reserved_quantity + o.sold_quantity + o.withheld_quantity > o.total_quantity
                OR o.reserved_quantity < 0 OR o.sold_quantity < 0 OR o.withheld_quantity < 0
              )
    ) THEN
        RAISE EXCEPTION '재고 정합성 위반: 1) 결과 참고';
    END IF;
END $$;
