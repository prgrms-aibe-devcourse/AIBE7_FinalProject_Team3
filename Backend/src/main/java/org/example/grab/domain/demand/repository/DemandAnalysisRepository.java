package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.InitialRecommendation;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.MarketSample;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class DemandAnalysisRepository {

    // demand 도메인이 drop·wish·order 엔티티에 의존하지 않도록 JDBC로 필요한 컬럼만 조회한다
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record TargetDrop(long dropId, long sellerId, String status, Long categoryId, OffsetDateTime saleStartsAt) {
    }

    public Optional<TargetDrop> findTargetDrop(long dropId) {
        String sql = """
                SELECT d.id, d.seller_id, d.status, d.category_id, d.sale_starts_at
                FROM drops d
                WHERE d.id = :dropId
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("dropId", dropId),
                (resultSet, rowNumber) -> new TargetDrop(
                        resultSet.getLong("id"),
                        resultSet.getLong("seller_id"),
                        resultSet.getString("status"),
                        resultSet.getObject("category_id", Long.class),
                        resultSet.getObject("sale_starts_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    public long countActiveWishes(long dropId) {
        String sql = "SELECT COUNT(*) FROM wishes w WHERE w.drop_id = :dropId AND w.canceled_at IS NULL";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("dropId", dropId), Long.class);
        return Objects.requireNonNullElse(count, 0L);
    }

    // 재등록하면 activated_at을 덮어쓰므로 활성 WISH의 최근 등록일 기준 근사치다(DEMAND.md 1.1)
    public Map<LocalDate, Long> countDailyActiveWishes(long dropId, OffsetDateTime from) {
        String sql = """
                SELECT CAST(w.activated_at AT TIME ZONE 'Asia/Seoul' AS DATE) AS wish_date, COUNT(*) AS wish_count
                FROM wishes w
                WHERE w.drop_id = :dropId AND w.canceled_at IS NULL AND w.activated_at >= :from
                GROUP BY wish_date
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("from", from);
        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getObject("wish_date", LocalDate.class),
                        resultSet.getLong("wish_count")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // 옵션 표시명은 그룹 sort_order 순으로 값을 " / "로 잇고, 값 매핑이 없는 기본 옵션은 "기본"이다
    public List<DemandOption> findActiveOptions(long dropId) {
        String sql = """
                SELECT op.id, op.total_quantity, op.sort_order,
                       COALESCE((SELECT STRING_AGG(v.value, ' / ' ORDER BY g.sort_order, g.id)
                                 FROM drop_option_value_maps m
                                 JOIN drop_option_groups g ON g.id = m.group_id
                                 JOIN drop_option_values v ON v.id = m.value_id
                                 WHERE m.option_id = op.id), '기본') AS name
                FROM drop_options op
                WHERE op.drop_id = :dropId AND op.is_active = TRUE
                ORDER BY op.sort_order, op.id
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("dropId", dropId),
                (resultSet, rowNumber) -> new DemandOption(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getInt("total_quantity"),
                        resultSet.getInt("sort_order")));
    }

    /*
        본인의 같은 카테고리 종료 DROP을 최근 종료순으로 최대 limit개 골라 전환 집계를 합친다(DEMAND.md 1.2·1.4).
        converted는 활성 WISH 사용자 중 결제 확정·미취소 주문이 있는 사용자 수, quantity는 그 사용자 주문의 수량 합계다.
        WISH 없이 구매한 사용자는 세지 않는다.
     */
    public ConversionSample findOwnConversionSample(long sellerId, long categoryId, long excludedDropId, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("categoryId", categoryId)
                .addValue("excludedDropId", excludedDropId)
                .addValue("limit", limit);
        String sql = CONVERSION_AGGREGATE.formatted("d.seller_id = :sellerId AND d.id <> :excludedDropId");
        return jdbcTemplate.queryForObject(sql, parameters, (resultSet, rowNumber) -> new ConversionSample(
                resultSet.getInt("drop_count"),
                resultSet.getLong("wishers"),
                resultSet.getLong("converted"),
                resultSet.getLong("quantity")));
    }

    /*
        시장 평균 표본은 다른 판매자의 같은 카테고리 종료 DROP이다(DEMAND.md 1.2·1.3).
        본인 DROP을 섞으면 합산값에서 자기 실적을 빼 다른 판매자 값을 역산할 수 있어 제외한다.
        closedBefore(오늘 0시 KST) 이전 종료분만 써서 하루 동안 값이 바뀌지 않게 한다.
        최소 집계 단위 판정에 쓰도록 표본의 서로 다른 판매자 수도 함께 센다. 이 합계는 서버 안에서만 쓴다.
     */
    public MarketSample findMarketConversionSample(long sellerId, long categoryId, OffsetDateTime closedBefore, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("categoryId", categoryId)
                .addValue("closedBefore", closedBefore)
                .addValue("limit", limit);
        String sql = CONVERSION_AGGREGATE.formatted("d.seller_id <> :sellerId AND d.closed_at < :closedBefore");
        return jdbcTemplate.queryForObject(sql, parameters, (resultSet, rowNumber) -> new MarketSample(
                resultSet.getInt("drop_count"),
                resultSet.getInt("seller_count"),
                resultSet.getLong("wishers"),
                resultSet.getLong("converted"),
                resultSet.getLong("quantity")));
    }

    // 공개 시점 재고 추천 기록(drop_stock_recommendations)의 요약. 수량 범위는 저장한 응답 스냅샷에서 읽는다
    public Optional<InitialRecommendation> findInitialRecommendation(long dropId) {
        String sql = """
                SELECT r.created_at, r.basis, r.recommended_total, r.prepared_total,
                       CAST(r.result -> 'recommendation' ->> 'low' AS INT) AS low,
                       CAST(r.result -> 'recommendation' ->> 'expected' AS INT) AS expected,
                       CAST(r.result -> 'recommendation' ->> 'high' AS INT) AS high
                FROM drop_stock_recommendations r
                WHERE r.drop_id = :dropId
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("dropId", dropId),
                (resultSet, rowNumber) -> {
                    String basis = resultSet.getString("basis");
                    return new InitialRecommendation(
                            resultSet.getObject("created_at", OffsetDateTime.class),
                            basis == null ? null : ConversionBasis.valueOf(basis),
                            resultSet.getObject("low", Integer.class),
                            resultSet.getObject("expected", Integer.class),
                            resultSet.getObject("high", Integer.class),
                            resultSet.getObject("recommended_total", Integer.class),
                            resultSet.getInt("prepared_total"));
                })
                .stream().findFirst();
    }

    // 판매 시작 시점 기록(DEMAND.md 2.3). DROP당 한 건이며 이미 있으면 남기지 않는다
    public void saveSnapshot(long dropId, String calculationVersion, String conversionBasis, String resultJson,
                             long activeWishCount, Integer expectedTotal, Integer recommendedTotal,
                             int preparedTotal, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO demand_analysis_snapshots (drop_id, calculation_version, conversion_basis, result,
                                                       active_wish_count, expected_total, recommended_total,
                                                       prepared_total, created_at)
                VALUES (:dropId, :calculationVersion, :conversionBasis, CAST(:result AS JSONB), :activeWishCount,
                        :expectedTotal, :recommendedTotal, :preparedTotal, :createdAt)
                ON CONFLICT (drop_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("calculationVersion", calculationVersion)
                .addValue("conversionBasis", conversionBasis)
                .addValue("result", resultJson)
                .addValue("activeWishCount", activeWishCount)
                .addValue("expectedTotal", expectedTotal)
                .addValue("recommendedTotal", recommendedTotal)
                .addValue("preparedTotal", preparedTotal)
                .addValue("createdAt", createdAt));
    }

    // %s에는 표본 DROP을 고르는 판매자·기간 조건이 들어간다. 나머지 표본 조건과 전환 집계는 두 기준이 같다.
    private static final String CONVERSION_AGGREGATE = """
            WITH samples AS (
                SELECT d.id, d.seller_id
                FROM drops d
                WHERE %s AND d.category_id = :categoryId
                  AND d.status = 'ENDED' AND d.close_reason = 'TIME_EXPIRED'
                  AND EXISTS (SELECT 1 FROM wishes w WHERE w.drop_id = d.id AND w.canceled_at IS NULL)
                ORDER BY d.closed_at DESC, d.id DESC
                LIMIT :limit
            ), buyers AS (
                SELECT o.drop_id, o.buyer_id, SUM(oi.quantity) AS quantity
                FROM orders o
                JOIN samples s ON s.id = o.drop_id
                JOIN order_items oi ON oi.order_id = o.id
                WHERE o.paid_at IS NOT NULL AND o.canceled_at IS NULL
                GROUP BY o.drop_id, o.buyer_id
            )
            SELECT (SELECT COUNT(*) FROM samples) AS drop_count,
                   (SELECT COUNT(DISTINCT seller_id) FROM samples) AS seller_count,
                   COUNT(w.id) AS wishers,
                   COUNT(b.buyer_id) AS converted,
                   COALESCE(SUM(b.quantity), 0) AS quantity
            FROM samples s
            JOIN wishes w ON w.drop_id = s.id AND w.canceled_at IS NULL
            LEFT JOIN buyers b ON b.drop_id = w.drop_id AND b.buyer_id = w.user_id
            """;
}
