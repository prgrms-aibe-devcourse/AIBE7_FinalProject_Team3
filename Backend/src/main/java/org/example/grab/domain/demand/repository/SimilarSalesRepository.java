package org.example.grab.domain.demand.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
    비슷한 DROP 실적의 표본(DEMAND.md 7.1). 후보는 같은 카테고리에서 기간 만료 또는 매진으로 끝난 DROP이다.
    다른 판매자 표본에 판매자 ID를 함께 읽지만 최소 집계 단위 판정에만 서버 안에서 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class SimilarSalesRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 종료 DROP 하나의 결과. sellOutMinutes는 매진된 DROP만, wishAtStart는 판매 시작 기록(2.3)이 있을 때만 값이 있다
    public record SimilarSample(long dropId, long sellerId, int prepared, int sold, boolean soldOut,
                                Integer sellOutMinutes, Integer wishAtStart) {
    }

    // OWN: 본인, OTHERS: 다른 판매자, NEW_SELLERS: 종료 DROP이 기준 이하인 다른 판매자
    public enum SellerScope {
        OWN,
        OTHERS,
        NEW_SELLERS
    }

    /*
        priceRange가 있으면 대표 가격으로 거르고, queryVector가 있으면 코사인 거리가 maxDistance 이하인 DROP만 가까운 순으로 고른다.
        queryVector가 없으면 최근 종료순이다. closedBefore는 다른 판매자 표본에만 쓴다(1.3).
     */
    public record SampleQuery(SellerScope scope, long sellerId, long categoryId, long excludedDropId,
                              OffsetDateTime closedBefore, BigDecimal minPrice, BigDecimal maxPrice,
                              String queryVector, double maxDistance, int maxPeerDrops, int limit) {
    }

    public List<SimilarSample> findSamples(SampleQuery query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", query.sellerId())
                .addValue("categoryId", query.categoryId())
                .addValue("excludedDropId", query.excludedDropId())
                .addValue("closedBefore", query.closedBefore())
                .addValue("maxPeerDrops", query.maxPeerDrops())
                .addValue("limit", query.limit());
        String sellerCondition = switch (query.scope()) {
            case OWN -> "d.seller_id = :sellerId";
            case OTHERS -> "d.seller_id <> :sellerId AND d.closed_at < :closedBefore";
            case NEW_SELLERS -> "d.seller_id <> :sellerId AND d.closed_at < :closedBefore"
                    + " AND (SELECT COUNT(*) FROM drops p WHERE p.seller_id = d.seller_id AND p.status = 'ENDED')"
                    + " <= :maxPeerDrops";
        };
        List<String> conditions = new ArrayList<>();
        if (query.minPrice() != null) {
            conditions.add("reference_price BETWEEN :minPrice AND :maxPrice");
            parameters.addValue("minPrice", query.minPrice()).addValue("maxPrice", query.maxPrice());
        }
        String distance = "NULL::FLOAT8";
        String order = "closed_at DESC, id DESC";
        if (query.queryVector() != null) {
            // 임베딩이 없는 DROP은 거리가 NULL이라 조건에서 빠진다
            distance = "(SELECT e.embedding <=> CAST(:queryVector AS vector) FROM drop_embeddings e WHERE e.drop_id = d.id)";
            conditions.add("distance <= :maxDistance");
            order = "distance, closed_at DESC, id DESC";
            parameters.addValue("queryVector", query.queryVector()).addValue("maxDistance", query.maxDistance());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return jdbcTemplate.query(SAMPLES.formatted(distance, sellerCondition, where, order), parameters, SAMPLE);
    }

    // 내 종료 DROP 수(카테고리와 무관). 0이면 첫 판매다(7.2). 비슷한 단계 판매자 판정과 같은 기준이다
    public long countOwnEndedDrops(long sellerId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM drops WHERE seller_id = :sellerId AND status = 'ENDED'
                """, new MapSqlParameterSource("sellerId", sellerId), Long.class);
        return count == null ? 0 : count;
    }

    // WISH 단계 대상의 저장된 임베딩(pgvector 입력 형식)과 대표 가격
    public Optional<String> findEmbedding(long dropId) {
        return jdbcTemplate.queryForList("SELECT embedding::text FROM drop_embeddings WHERE drop_id = :dropId",
                new MapSqlParameterSource("dropId", dropId), String.class).stream().findFirst();
    }

    // 활성 옵션의 준비 수량 가중 평균 단가. 준비 수량이 모두 0이면 단순 평균이다(5.1)
    public long findReferencePrice(long dropId) {
        Long price = jdbcTemplate.queryForObject("""
                SELECT COALESCE(ROUND(SUM(unit_price * total_quantity)::NUMERIC / NULLIF(SUM(total_quantity), 0)),
                                ROUND(AVG(unit_price)), 0)
                FROM drop_options
                WHERE drop_id = :dropId AND is_active
                """, new MapSqlParameterSource("dropId", dropId), Long.class);
        return price == null ? 0 : price;
    }

    private static final RowMapper<SimilarSample> SAMPLE = (resultSet, rowNumber) -> new SimilarSample(
            resultSet.getLong("id"),
            resultSet.getLong("seller_id"),
            resultSet.getInt("prepared"),
            resultSet.getInt("sold"),
            resultSet.getBoolean("sold_out"),
            resultSet.getObject("sell_out_minutes", Integer.class),
            resultSet.getObject("wish_at_start", Integer.class));

    /*
        %s에는 차례로 요청 임베딩과의 거리, 판매자·기간 조건, 가격·유사도 조건, 정렬이 들어간다.
        DROP별로 옵션을 합산한 뒤 대표 가격·거리로 걸러야 해서 CTE로 나눈다.
        매진까지 걸린 시간은 실제 판매 시작(grab_started_at)부터 종료 시각까지의 분이다.
     */
    private static final String SAMPLES = """
            WITH candidates AS (
                SELECT d.id, d.seller_id, d.closed_at,
                       d.close_reason = 'SOLD_OUT' AS sold_out,
                       CASE WHEN d.close_reason = 'SOLD_OUT' AND d.grab_started_at IS NOT NULL
                            THEN ROUND(EXTRACT(EPOCH FROM (d.closed_at - d.grab_started_at)) / 60.0)::INT END
                           AS sell_out_minutes,
                       (SELECT s.active_wish_count FROM demand_analysis_snapshots s WHERE s.drop_id = d.id) AS wish_at_start,
                       SUM(o.total_quantity) AS prepared,
                       SUM(o.sold_quantity) AS sold,
                       ROUND(SUM(o.unit_price * o.total_quantity)::NUMERIC / SUM(o.total_quantity)) AS reference_price,
                       %s AS distance
                FROM drops d
                JOIN drop_options o ON o.drop_id = d.id
                WHERE %s AND d.category_id = :categoryId AND d.id <> :excludedDropId
                  AND d.status = 'ENDED' AND d.close_reason IN ('TIME_EXPIRED', 'SOLD_OUT')
                GROUP BY d.id, d.seller_id, d.closed_at, d.close_reason, d.grab_started_at
                HAVING SUM(o.total_quantity) > 0
            )
            SELECT id, seller_id, prepared, sold, sold_out, sell_out_minutes, wish_at_start
            FROM candidates
            %s
            ORDER BY %s
            LIMIT :limit
            """;
}
