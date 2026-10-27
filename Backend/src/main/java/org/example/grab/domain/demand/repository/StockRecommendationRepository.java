package org.example.grab.domain.demand.repository;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.demand.dto.SalesSample;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
    생성 단계 재고 추천의 판매 실적 표본(DEMAND.md 5.1·5.4)과 공개 시점 기록(5.6).
    demand 도메인이 drop 엔티티에 의존하지 않도록 JDBC로 필요한 컬럼만 조회한다.
 */
@Repository
@RequiredArgsConstructor
public class StockRecommendationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record PublishedDrop(long dropId, long sellerId, Long categoryId, List<PublishedOption> options) {
    }

    public record PublishedOption(long optionId, long unitPrice, int quantity) {
    }

    // 본인의 같은 카테고리 종료 DROP. 가격 범위가 없으면(대표 가격 0) 가격 조건 없이 고른다
    public List<SalesSample> findOwnSales(long sellerId, long categoryId, PriceRange priceRange, int limit) {
        return findSales("d.seller_id = :sellerId", sellerId, categoryId, null, priceRange, limit);
    }

    /*
        다른 판매자의 같은 카테고리 종료 DROP(DEMAND.md 1.3 보호 규칙). 본인 DROP을 섞지 않고,
        closedBefore(오늘 0시 KST) 이전 종료분만 써서 하루 동안 값이 바뀌지 않게 한다.
        최소 집계 단위 판정에 쓰도록 판매자 ID를 함께 읽지만 서버 안에서만 쓴다.
     */
    public List<SalesSample> findMarketSales(long sellerId, long categoryId, OffsetDateTime closedBefore,
                                             PriceRange priceRange, int limit) {
        return findSales("d.seller_id <> :sellerId AND d.closed_at < :closedBefore",
                sellerId, categoryId, closedBefore, priceRange, limit);
    }

    public Optional<PublishedDrop> findPublishedDrop(long dropId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("dropId", dropId);
        List<PublishedOption> options = jdbcTemplate.query("""
                SELECT o.id, o.unit_price, o.total_quantity
                FROM drop_options o
                WHERE o.drop_id = :dropId AND o.is_active = TRUE
                ORDER BY o.sort_order, o.id
                """, parameters, (resultSet, rowNumber) -> new PublishedOption(
                resultSet.getLong("id"), resultSet.getLong("unit_price"), resultSet.getInt("total_quantity")));
        return jdbcTemplate.query("SELECT id, seller_id, category_id FROM drops WHERE id = :dropId", parameters,
                        (resultSet, rowNumber) -> new PublishedDrop(
                                resultSet.getLong("id"),
                                resultSet.getLong("seller_id"),
                                resultSet.getObject("category_id", Long.class),
                                options))
                .stream().findFirst();
    }

    // DROP당 한 건만 남긴다. 같은 DROP을 다시 기록하려 하면 무시한다(공개는 DRAFT → WISH 한 번뿐이다)
    public void saveSnapshot(long dropId, String calculationVersion, String basis, String resultJson,
                             Integer recommendedTotal, int preparedTotal, String preparedQuantitiesJson,
                             boolean applied, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO drop_stock_recommendations (drop_id, calculation_version, basis, result, recommended_total,
                                                        prepared_total, prepared_quantities, applied, created_at)
                VALUES (:dropId, :calculationVersion, :basis, CAST(:result AS JSONB), :recommendedTotal,
                        :preparedTotal, CAST(:preparedQuantities AS JSONB), :applied, :createdAt)
                ON CONFLICT (drop_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("calculationVersion", calculationVersion)
                .addValue("basis", basis)
                .addValue("result", resultJson)
                .addValue("recommendedTotal", recommendedTotal)
                .addValue("preparedTotal", preparedTotal)
                .addValue("preparedQuantities", preparedQuantitiesJson)
                .addValue("applied", applied)
                .addValue("createdAt", createdAt));
    }

    // 표본 대표 가격의 허용 범위(양 끝 포함)
    public record PriceRange(BigDecimal min, BigDecimal max) {
    }

    private List<SalesSample> findSales(String sellerCondition, long sellerId, long categoryId,
                                        OffsetDateTime closedBefore, PriceRange priceRange, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("categoryId", categoryId)
                .addValue("closedBefore", closedBefore)
                .addValue("limit", limit);
        String priceCondition = "";
        if (priceRange != null) {
            priceCondition = "WHERE reference_price BETWEEN :minPrice AND :maxPrice";
            parameters.addValue("minPrice", priceRange.min()).addValue("maxPrice", priceRange.max());
        }
        return jdbcTemplate.query(SALES_SAMPLES.formatted(sellerCondition, priceCondition), parameters, SALES_SAMPLE);
    }

    private static final RowMapper<SalesSample> SALES_SAMPLE = (resultSet, rowNumber) -> new SalesSample(
            resultSet.getLong("seller_id"),
            resultSet.getLong("reference_price"),
            resultSet.getInt("prepared"),
            resultSet.getInt("sold"),
            resultSet.getBoolean("sold_out"));

    /*
        첫 %s에는 판매자·기간 조건, 둘째 %s에는 가격 조건이 들어간다.
        DROP별로 옵션을 합산한 뒤 가격 조건을 걸어야 "대표 가격"으로 고를 수 있어 CTE로 나눈다.
        품절은 종료 시점 가용 재고 합계가 0인 DROP이다(SOLD_OUT 조기 종료는 쓰지 않는다, ERD.md 1.3).
     */
    private static final String SALES_SAMPLES = """
            WITH candidates AS (
                SELECT d.id, d.seller_id, d.closed_at,
                       SUM(o.total_quantity) AS prepared,
                       SUM(o.sold_quantity) AS sold,
                       SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity) AS available,
                       ROUND(SUM(o.unit_price * o.total_quantity)::NUMERIC / SUM(o.total_quantity)) AS reference_price
                FROM drops d
                JOIN drop_options o ON o.drop_id = d.id
                WHERE %s AND d.category_id = :categoryId
                  AND d.status = 'ENDED' AND d.close_reason = 'TIME_EXPIRED'
                GROUP BY d.id, d.seller_id, d.closed_at
                HAVING SUM(o.total_quantity) > 0
            )
            SELECT seller_id, reference_price, prepared, sold, available = 0 AS sold_out
            FROM candidates
            %s
            ORDER BY closed_at DESC, id DESC
            LIMIT :limit
            """;
}
